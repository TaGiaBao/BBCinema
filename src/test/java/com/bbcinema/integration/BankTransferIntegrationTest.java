package com.bbcinema.integration;

import com.bbcinema.entity.Booking;
import com.bbcinema.entity.Payment;
import com.bbcinema.entity.Showtime;
import com.bbcinema.entity.User;
import com.bbcinema.entity.enums.BookingStatus;
import com.bbcinema.entity.enums.PaymentMethod;
import com.bbcinema.entity.enums.PaymentStatus;
import com.bbcinema.entity.enums.Role;
import com.bbcinema.repository.PaymentRepository;
import com.bbcinema.scheduler.BankTransferPoller;
import com.bbcinema.service.BankTransferService;
import com.bbcinema.service.SepayClient;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Paying by bank transfer (VietQR): online, at the counter, matched by SePay, by staff or by the demo button. */
@TestPropertySource(properties = {
        "app.bank-qr.bank-code=MB", "app.bank-qr.account-no=0123456789", "app.bank-qr.account-name=BBCINEMA TEST",
        "app.bank-qr.sepay-api-token=test-token", "app.bank-qr.demo-confirm=true",
        "app.bank-qr.poll-interval-ms=3600000"})
class BankTransferIntegrationTest extends IntegrationTestBase {

    @Autowired BankTransferService bankTransferService;
    @Autowired BankTransferPoller poller;
    @Autowired PaymentRepository paymentRepository;
    @MockitoBean SepayClient sepayClient;

    private Cookie login(User user) {
        return new Cookie("BB_TOKEN", token(user));
    }

    private Booking pendingBooking(User user) {
        Showtime st = showtimeTomorrow();
        return bookingService.holdSeats(user.getId(), st.getId(), freeSeats(st.getId(), 2));
    }

    @Test
    void memberPaysByScanningTheQrAndTheTicketOpensWhenTheMoneyArrives() throws Exception {
        User user = member(25);
        Booking booking = pendingBooking(user);
        String code = booking.getCode();

        mvc.perform(get("/booking/" + code + "/checkout").cookie(login(user)))
                .andExpect(content().string(containsString("value=\"BANK_QR\"")))
                .andExpect(content().string(not(containsString("value=\"MOCK\""))));
        mvc.perform(post("/booking/" + code + "/pay").with(csrf()).cookie(login(user)).param("method", "MOCK"))
                .andExpect(flash().attribute("error", containsString("chọn một hình thức")));
        mvc.perform(post("/booking/" + code + "/pay").with(csrf()).cookie(login(user)).param("method", "BANK_QR"))
                .andExpect(redirectedUrl("/booking/" + code + "/bank-transfer"));
        mvc.perform(get("/booking/" + code + "/bank-transfer").cookie(login(user)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("BBC " + code)))
                .andExpect(content().string(containsString("0123456789")));
        mvc.perform(get("/booking/" + code + "/bank-qr.png").cookie(login(user)))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("image/png"));
        mvc.perform(get("/booking/" + code + "/payment-status").cookie(login(user)))
                .andExpect(jsonPath("$.status").value("PENDING"));

        // Too little money is ignored, the right amount pays the booking, a repeated report changes nothing.
        assertThat(bankTransferService.handleIncoming("MBVCB BBC " + code, new BigDecimal("1000"), "FT1")).isFalse();
        assertThat(bookingRepository.findDetailedByCode(code).orElseThrow().getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(bankTransferService.handleIncoming("bbc" + code.toLowerCase() + " ck", booking.getTotalAmount(), "FT2")).isTrue();
        assertThat(bankTransferService.handleIncoming("BBC " + code, booking.getTotalAmount(), "FT3")).isTrue();

        mvc.perform(get("/booking/" + code + "/payment-status").cookie(login(user)))
                .andExpect(jsonPath("$.status").value("PAID"));
        Payment payment = paymentRepository.findByTransactionRef("QR-" + code).orElseThrow();
        assertThat(payment.getMethod()).isEqualTo(PaymentMethod.BANK_QR);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(payment.getGatewayTransactionNo()).isEqualTo("FT2");
        mvc.perform(get("/booking/" + code).cookie(login(user)))
                .andExpect(content().string(containsString("Chuyển khoản QR")));
    }

    @Test
    void sepayPollerConfirmsTheBookingNamedInTheTransfer() {
        User user = member(30);
        Booking booking = pendingBooking(user);
        bookingService.startBankTransfer(booking.getCode(), user.getId(), Role.USER);
        when(sepayClient.incomingSince(any())).thenReturn(List.of(
                new SepayClient.Transaction("x1", new BigDecimal("50000"), "tien an trua", "FT9"),
                new SepayClient.Transaction("x2", booking.getTotalAmount(), "MBVCB.1.BBC " + booking.getCode() + ".CT", "FT10")));

        poller.poll();

        Booking paid = bookingRepository.findDetailedByCode(booking.getCode()).orElseThrow();
        assertThat(paid.getStatus()).isEqualTo(BookingStatus.PAID);
        assertThat(paymentRepository.findByTransactionRef("QR-" + booking.getCode()).orElseThrow().getGatewayTransactionNo())
                .isEqualTo("SePay FT10");
    }

    @Test
    void demoButtonPlaysTheBank() throws Exception {
        User user = member(22);
        Booking booking = pendingBooking(user);
        bookingService.startBankTransfer(booking.getCode(), user.getId(), Role.USER);
        mvc.perform(post("/booking/" + booking.getCode() + "/bank-transfer/demo-confirm").with(csrf()).cookie(login(user)))
                .andExpect(redirectedUrl("/booking/" + booking.getCode()));
        assertThat(bookingRepository.findDetailedByCode(booking.getCode()).orElseThrow().isPaid()).isTrue();
    }

    @Test
    void counterSellsByCardWithApprovalCodeAndByTransferConfirmedByStaff() throws Exception {
        User staff = account(Role.STAFF);
        Showtime st = showtimeTomorrow();
        List<Long> seats = freeSeats(st.getId(), 3);

        mvc.perform(post("/staff/sell/" + st.getId()).with(csrf()).cookie(login(staff))
                        .param("showtimeSeatIds", seats.get(0).toString()).param("paymentMethod", "CARD"))
                .andExpect(flash().attribute("error", containsString("mã chuẩn chi")));
        mvc.perform(post("/staff/sell/" + st.getId()).with(csrf()).cookie(login(staff))
                        .param("showtimeSeatIds", seats.get(0).toString()).param("paymentMethod", "CARD")
                        .param("cardApprovalCode", "a1b2c3"))
                .andExpect(redirectedUrlPattern("/staff/tickets/*"));
        Payment card = paymentRepository.findAll().stream()
                .filter(p -> p.getMethod() == PaymentMethod.CARD).reduce((a, b) -> b).orElseThrow();
        assertThat(card.getGatewayTransactionNo()).isEqualTo("A1B2C3");

        mvc.perform(post("/staff/sell/" + st.getId()).with(csrf()).cookie(login(staff))
                        .param("showtimeSeatIds", seats.get(1).toString()).param("paymentMethod", "MOMO"))
                .andExpect(flash().attribute("error", containsString("chưa được kết nối")));

        String location = mvc.perform(post("/staff/sell/" + st.getId()).with(csrf()).cookie(login(staff))
                        .param("showtimeSeatIds", seats.get(2).toString()).param("paymentMethod", "BANK_QR"))
                .andExpect(redirectedUrlPattern("/staff/bank-transfer/*"))
                .andReturn().getResponse().getRedirectedUrl();
        String code = location.substring(location.lastIndexOf('/') + 1);
        mvc.perform(get(location).cookie(login(staff)))
                .andExpect(content().string(containsString("BBC " + code)))
                .andExpect(content().string(containsString("Đã nhận tiền")));
        mvc.perform(get(location + "/status").cookie(login(staff))).andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(post(location + "/confirm").with(csrf()).cookie(login(staff)))
                .andExpect(redirectedUrl("/staff/tickets/" + code));
        Booking paid = bookingRepository.findDetailedByCode(code).orElseThrow();
        assertThat(paid.isPaid()).isTrue();
        assertThat(paymentRepository.findByTransactionRef("QR-" + code).orElseThrow().getGatewayTransactionNo())
                .startsWith("Xác nhận bởi");
    }

    @Test
    void customersCannotUseTheStaffConfirmation() throws Exception {
        User user = member(25);
        Booking booking = pendingBooking(user);
        mvc.perform(post("/staff/bank-transfer/" + booking.getCode() + "/confirm").with(csrf()).cookie(login(user)))
                .andExpect(status().isForbidden());
    }
}
