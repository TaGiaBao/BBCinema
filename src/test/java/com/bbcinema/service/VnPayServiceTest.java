package com.bbcinema.service;

import com.bbcinema.TestFixtures;
import com.bbcinema.entity.Booking;
import com.bbcinema.entity.Payment;
import com.bbcinema.entity.enums.BookingStatus;
import com.bbcinema.entity.enums.PaymentMethod;
import com.bbcinema.entity.enums.PaymentStatus;
import com.bbcinema.entity.enums.Role;
import com.bbcinema.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VnPayServiceTest {

    @Mock PaymentRepository paymentRepository;
    @Mock BookingService bookingService;

    VnPayService service() {
        return new VnPayService(TestFixtures.properties(), paymentRepository, bookingService, TestFixtures.clock());
    }

    @Test
    void hmacMatchesKnownValue() {
        // Reference value computed with: echo -n "abc" | openssl dgst -sha512 -hmac "key"
        assertThat(VnPayService.hmacSha512("key", "abc"))
                .isEqualTo("3926a207c8c42b0c41792cbd3e1a1aaaf5f7a25704f62dfc939c4987dd7ce060009c5bb1c2447355b3216f10b537e9afa7b64a4e5391b0d631172d07939e087a");
    }

    @Test
    void queryIsSortedEncodedAndSkipsEmptyValues() {
        Map<String, String> params = new TreeMap<>(Map.of("vnp_OrderInfo", "Thanh toan ve A", "vnp_Amount", "100", "vnp_Empty", ""));
        assertThat(VnPayService.buildQuery(params)).isEqualTo("vnp_Amount=100&vnp_OrderInfo=Thanh+toan+ve+A");
    }

    private Booking pending() {
        Booking b = new Booking();
        b.setId(1L);
        b.setCode("ABCD2345");
        b.setStatus(BookingStatus.PENDING);
        b.setTotalAmount(BigDecimal.valueOf(150_000));
        b.setExpiresAt(TestFixtures.NOW.plusMinutes(4));
        return b;
    }

    @Test
    void paymentUrlIsSignedAndHasAmountTimes100() {
        when(bookingService.getForViewer("ABCD2345", 1L, Role.USER)).thenReturn(pending());

        String url = service().createPaymentUrl("ABCD2345", 1L, "1.2.3.4");

        Map<String, String> query = parse(url);
        assertThat(url).startsWith("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?");
        assertThat(query).containsEntry("vnp_Amount", "15000000").containsEntry("vnp_TmnCode", "TESTCODE")
                .containsEntry("vnp_IpAddr", "1.2.3.4").containsEntry("vnp_CreateDate", "20261119100000");
        String hash = query.remove("vnp_SecureHash");
        assertThat(hash).isEqualTo(VnPayService.hmacSha512("SECRETKEY123", VnPayService.buildQuery(new TreeMap<>(query))));
        verify(paymentRepository).save(argThat(p -> p.getMethod() == PaymentMethod.VNPAY && p.getStatus() == PaymentStatus.PENDING));
    }

    @Test
    void validCallbackConfirmsPayment() {
        Booking booking = pending();
        Payment payment = new Payment();
        payment.setBooking(booking);
        payment.setAmount(BigDecimal.valueOf(150_000));
        payment.setStatus(PaymentStatus.PENDING);
        when(paymentRepository.findByTransactionRef("ABCD2345100000")).thenReturn(Optional.of(payment));

        Map<String, String> params = signed(Map.of("vnp_TxnRef", "ABCD2345100000", "vnp_Amount", "15000000",
                "vnp_ResponseCode", "00", "vnp_TransactionStatus", "00", "vnp_TransactionNo", "999"));
        VnPayService.VnPayResult result = service().handleCallback(params);

        assertThat(result.success()).isTrue();
        assertThat(result.bookingCode()).isEqualTo("ABCD2345");
        verify(bookingService).confirmPayment(booking, PaymentMethod.VNPAY, "ABCD2345100000", "999");
    }

    @Test
    void tamperedCallbackIsRejected() {
        Map<String, String> params = signed(Map.of("vnp_TxnRef", "X", "vnp_Amount", "100", "vnp_ResponseCode", "00", "vnp_TransactionStatus", "00"));
        params.put("vnp_Amount", "1");
        VnPayService.VnPayResult result = service().handleCallback(params);
        assertThat(result.validSignature()).isFalse();
        verifyNoInteractions(bookingService);
    }

    @Test
    void failedGatewayResultMarksPaymentFailed() {
        Payment payment = new Payment();
        payment.setBooking(pending());
        payment.setAmount(BigDecimal.valueOf(150_000));
        payment.setStatus(PaymentStatus.PENDING);
        when(paymentRepository.findByTransactionRef("T1")).thenReturn(Optional.of(payment));
        Map<String, String> params = signed(Map.of("vnp_TxnRef", "T1", "vnp_Amount", "15000000", "vnp_ResponseCode", "24", "vnp_TransactionStatus", "02"));

        VnPayService.VnPayResult result = service().handleCallback(params);

        assertThat(result.success()).isFalse();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        verify(bookingService, never()).confirmPayment(any(), any(), any(), any());
    }

    @Test
    void amountMismatchIsRejected() {
        Payment payment = new Payment();
        payment.setBooking(pending());
        payment.setAmount(BigDecimal.valueOf(150_000));
        when(paymentRepository.findByTransactionRef("T2")).thenReturn(Optional.of(payment));
        Map<String, String> params = signed(Map.of("vnp_TxnRef", "T2", "vnp_Amount", "100", "vnp_ResponseCode", "00", "vnp_TransactionStatus", "00"));
        assertThat(service().handleCallback(params).success()).isFalse();
    }

    private static Map<String, String> signed(Map<String, String> values) {
        Map<String, String> params = new TreeMap<>(values);
        String hash = VnPayService.hmacSha512("SECRETKEY123", VnPayService.buildQuery(params));
        Map<String, String> result = new HashMap<>(params);
        result.put("vnp_SecureHash", hash);
        return result;
    }

    private static Map<String, String> parse(String url) {
        Map<String, String> map = new HashMap<>();
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            map.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.US_ASCII));
        }
        return map;
    }
}
