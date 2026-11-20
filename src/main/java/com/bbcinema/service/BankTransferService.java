package com.bbcinema.service;

import com.bbcinema.config.BankQrProperties;
import com.bbcinema.entity.Booking;
import com.bbcinema.entity.enums.PaymentMethod;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.exception.NotFoundException;
import com.bbcinema.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Paying by bank transfer: the VietQR code shown to the customer, and matching the money that arrives
 * (from SePay, from staff, or from the demo button) to the booking named in the transfer message.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BankTransferService {

    /** Transfer message: "BBC" and the booking code. Banks may drop the space, so it is optional. */
    static final Pattern MESSAGE = Pattern.compile("BBC\\s*([A-HJ-NP-Z2-9]{8})(?![A-Z0-9])");

    private final BankQrProperties properties;
    private final BookingService bookingService;
    private final QrCodeService qrCodeService;
    private final PaymentRepository paymentRepository;

    /** What the payment page shows next to the QR code. */
    public record TransferInfo(String bankCode, String accountNo, String accountName, BigDecimal amount, String message) {
    }

    public boolean isAvailable() {
        return properties.isConfigured();
    }

    public boolean isAutomatic() {
        return properties.isAutomatic();
    }

    public boolean isDemoConfirmAllowed() {
        return properties.demoConfirm();
    }

    public static String messageFor(String bookingCode) {
        return "BBC " + bookingCode;
    }

    public TransferInfo info(Booking booking) {
        requireAvailable();
        return new TransferInfo(properties.bankCode(), properties.accountNo(), properties.accountName(),
                booking.getTotalAmount(), messageFor(booking.getCode()));
    }

    /** PNG of the VietQR code for this booking: account, amount and message are filled in for the customer. */
    public byte[] qrPng(Booking booking) {
        requireAvailable();
        String payload = VietQr.payload(properties.resolvedBin(), properties.accountNo().trim(),
                booking.getTotalAmount(), messageFor(booking.getCode()));
        return qrCodeService.png(payload, 320);
    }

    /** The booking code named in a transfer message, if any. */
    public static Optional<String> bookingCodeIn(String transferMessage) {
        if (transferMessage == null) {
            return Optional.empty();
        }
        Matcher m = MESSAGE.matcher(transferMessage.toUpperCase(Locale.ROOT));
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /**
     * One incoming bank transaction. Returns true when it paid a booking. Transfers for other purposes, for
     * unknown codes or for too little money are logged and ignored, so one bad transfer never stops the others.
     */
    public boolean handleIncoming(String transferMessage, BigDecimal amount, String bankReference) {
        Optional<String> code = bookingCodeIn(transferMessage);
        if (code.isEmpty()) {
            return false;
        }
        try {
            return bookingService.confirmBankTransfer(code.get(), amount, bankReference).isPaid();
        } catch (BusinessException | NotFoundException e) {
            log.warn("Bank transfer {} for {} not applied: {}", bankReference, code.get(), e.getMessage());
            return false;
        }
    }

    /** Demo only (app.bank-qr.demo-confirm): plays the bank's part and reports the full amount as received. */
    public Booking demoConfirm(String code) {
        if (!properties.demoConfirm()) {
            throw new BusinessException("Chức năng giả lập ngân hàng chỉ có ở bản demo");
        }
        Booking booking = bookingService.getByCode(code);
        return bookingService.confirmBankTransfer(code, booking.getTotalAmount(), "DEMO-" + code);
    }

    /** Staff saw the money in the cinema's banking app and confirm the transfer by hand. */
    public Booking confirmByStaff(String code, String staffName) {
        Booking booking = bookingService.getByCode(code);
        return bookingService.confirmBankTransfer(code, booking.getTotalAmount(), "Xác nhận bởi " + staffName);
    }

    /** When the oldest transfer still waiting for its money started; null when none is waiting. */
    public LocalDateTime oldestWaiting() {
        return paymentRepository.oldestWaiting(PaymentMethod.BANK_QR);
    }

    private void requireAvailable() {
        if (!properties.isConfigured()) {
            throw new BusinessException("Rạp chưa cấu hình tài khoản nhận chuyển khoản");
        }
    }
}
