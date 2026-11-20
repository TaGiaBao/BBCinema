package com.bbcinema.service;

import com.bbcinema.config.AppProperties;
import com.bbcinema.entity.Booking;
import com.bbcinema.entity.Payment;
import com.bbcinema.entity.enums.PaymentMethod;
import com.bbcinema.entity.enums.PaymentStatus;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * VNPay payment gateway, version 2.1.0 (sandbox). Builds the signed payment URL and checks the
 * signature of the data VNPay sends back. The signature is HMAC-SHA512 over the request parameters
 * sorted by name and URL-encoded.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VnPayService {

    private static final DateTimeFormatter VNP_DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final AppProperties properties;
    private final PaymentRepository paymentRepository;
    private final BookingService bookingService;
    private final Clock clock;

    public boolean isEnabled() {
        return properties.vnpay() != null && properties.vnpay().isConfigured();
    }

    /** Tells whoever runs the app why customers cannot pick VNPay; customers only see a short notice. */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void logWhenDisabled() {
        if (!isEnabled()) {
            log.info("VNPay is off: set VNPAY_TMN_CODE and VNPAY_HASH_SECRET in .env (free sandbox account at "
                    + "https://sandbox.vnpayment.vn/devreg/) to turn it on.");
        }
    }

    /** Creates a pending payment and returns the VNPay URL the browser is sent to. */
    @Transactional
    public String createPaymentUrl(String bookingCode, Long userId, String clientIp) {
        if (!isEnabled()) {
            throw new BusinessException("Cổng VNPay chưa được cấu hình. Vui lòng chọn thanh toán thử.");
        }
        Booking booking = bookingService.getForOwnerChange(bookingCode, userId, com.bbcinema.entity.enums.Role.USER);
        if (!booking.isPending()) {
            throw new BusinessException("Đơn " + booking.getCode() + " không ở trạng thái chờ thanh toán");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (booking.getExpiresAt() != null && booking.getExpiresAt().isBefore(now)) {
            throw new BusinessException("Đã hết thời gian giữ ghế. Vui lòng đặt lại.");
        }
        String txnRef = booking.getCode() + now.format(DateTimeFormatter.ofPattern("HHmmss"));

        Payment payment = new Payment();
        payment.setBooking(booking);
        payment.setMethod(PaymentMethod.VNPAY);
        payment.setStatus(PaymentStatus.PENDING);
        payment.setAmount(booking.getTotalAmount());
        payment.setTransactionRef(txnRef);
        payment.setCreatedAt(now);
        paymentRepository.save(payment);

        Map<String, String> params = new TreeMap<>();
        params.put("vnp_Version", "2.1.0");
        params.put("vnp_Command", "pay");
        params.put("vnp_TmnCode", properties.vnpay().tmnCode());
        params.put("vnp_Amount", booking.getTotalAmount().multiply(BigDecimal.valueOf(100)).toBigInteger().toString());
        params.put("vnp_CurrCode", "VND");
        params.put("vnp_TxnRef", txnRef);
        params.put("vnp_OrderInfo", "Thanh toan ve BBCinema " + booking.getCode());
        params.put("vnp_OrderType", "other");
        params.put("vnp_Locale", "vn");
        params.put("vnp_ReturnUrl", properties.baseUrl() + properties.vnpay().returnPath());
        params.put("vnp_IpAddr", clientIp == null || clientIp.isBlank() ? "127.0.0.1" : clientIp);
        params.put("vnp_CreateDate", now.format(VNP_DATE));
        LocalDateTime expire = booking.getExpiresAt() != null ? booking.getExpiresAt() : now.plusMinutes(15);
        params.put("vnp_ExpireDate", expire.format(VNP_DATE));

        String query = buildQuery(params);
        String hash = hmacSha512(properties.vnpay().hashSecret(), query);
        return properties.vnpay().payUrl() + "?" + query + "&vnp_SecureHash=" + hash;
    }

    /** Result of checking what VNPay sent back. */
    public record VnPayResult(boolean validSignature, boolean success, String bookingCode, String message) {
    }

    /**
     * Handles the data VNPay sends to the return URL (and to the IPN URL). When the signature is
     * valid and VNPay reports success, the booking is marked as paid.
     */
    @Transactional
    public VnPayResult handleCallback(Map<String, String> allParams) {
        Map<String, String> params = new TreeMap<>(allParams);
        String receivedHash = params.remove("vnp_SecureHash");
        params.remove("vnp_SecureHashType");
        if (!isEnabled() || receivedHash == null) {
            return new VnPayResult(false, false, null, "Thiếu chữ ký VNPay");
        }
        String expected = hmacSha512(properties.vnpay().hashSecret(), buildQuery(params));
        if (!expected.equalsIgnoreCase(receivedHash)) {
            log.warn("VNPay signature mismatch for txn {}", params.get("vnp_TxnRef"));
            return new VnPayResult(false, false, null, "Chữ ký không hợp lệ");
        }
        String txnRef = params.get("vnp_TxnRef");
        Payment payment = paymentRepository.findByTransactionRef(txnRef).orElse(null);
        if (payment == null) {
            return new VnPayResult(true, false, null, "Không tìm thấy giao dịch " + txnRef);
        }
        Booking booking = payment.getBooking();
        BigDecimal paidAmount = new BigDecimal(params.getOrDefault("vnp_Amount", "0")).divide(BigDecimal.valueOf(100));
        boolean gatewayOk = "00".equals(params.get("vnp_ResponseCode")) && "00".equals(params.get("vnp_TransactionStatus"));
        if (!gatewayOk) {
            if (payment.getStatus() == PaymentStatus.PENDING) {
                payment.setStatus(PaymentStatus.FAILED);
            }
            return new VnPayResult(true, false, booking.getCode(), "Giao dịch không thành công (mã " + params.get("vnp_ResponseCode") + ")");
        }
        if (paidAmount.compareTo(payment.getAmount()) != 0) {
            return new VnPayResult(true, false, booking.getCode(), "Số tiền thanh toán không khớp");
        }
        if (bookingService.isHoldExpired(booking)) {
            // VNPay took the money but the seat hold is over: do not issue a ticket. The payment is
            // recorded as failed with the gateway number so the cinema can refund it.
            payment.setStatus(PaymentStatus.FAILED);
            payment.setGatewayTransactionNo(params.get("vnp_TransactionNo"));
            log.warn("Late VNPay payment for booking {} (txn {}, VNPay no {}): hold expired, refund needed",
                    booking.getCode(), txnRef, params.get("vnp_TransactionNo"));
            return new VnPayResult(true, false, booking.getCode(),
                    "Đơn đã hết thời gian giữ ghế nên không thể xuất vé. Rạp sẽ hoàn tiền giao dịch này.");
        }
        bookingService.confirmPayment(booking, PaymentMethod.VNPAY, txnRef, params.get("vnp_TransactionNo"));
        return new VnPayResult(true, true, booking.getCode(), "Thanh toán thành công");
    }

    static String buildQuery(Map<String, String> sortedParams) {
        return sortedParams.entrySet().stream()
                .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.US_ASCII);
    }

    static String hmacSha512(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot compute HMAC-SHA512", e);
        }
    }
}
