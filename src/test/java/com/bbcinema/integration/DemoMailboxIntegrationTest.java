package com.bbcinema.integration;

import com.bbcinema.entity.Booking;
import com.bbcinema.entity.Showtime;
import com.bbcinema.entity.User;
import com.bbcinema.service.DemoMailbox;
import com.bbcinema.service.MailService;
import com.bbcinema.service.event.PasswordResetRequestedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** With the demo mailbox on and real mail off, sent e-mails can be read at /demo/mailbox. */
@TestPropertySource(properties = "app.demo-mailbox=true")
class DemoMailboxIntegrationTest extends IntegrationTestBase {

    @Autowired MailService mailService;
    @Autowired DemoMailbox mailbox;

    /** The listeners run in the background, after the transaction commits. */
    private DemoMailbox.DemoMail waitFor(Predicate<DemoMailbox.DemoMail> match) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            var found = mailbox.all().stream().filter(match).findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("No such e-mail in the demo mailbox");
    }

    @Test
    void passwordResetMailIsKeptAndShownWithoutAMailServer() throws Exception {
        mailService.onPasswordResetRequested(new PasswordResetRequestedEvent(
                "demo@test.vn", "Khách Demo", "http://localhost:8080/reset-password?token=abc"));
        long id = waitFor(m -> m.to().equals("demo@test.vn")).id();

        mvc.perform(get("/demo/mailbox"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("demo@test.vn")))
                .andExpect(content().string(containsString("[BBCinema] Đặt lại mật khẩu")));
        mvc.perform(get("/demo/mailbox/" + id))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("reset-password?token=abc")));
        mvc.perform(get("/demo/mailbox/999999")).andExpect(status().isNotFound());
    }

    @Test
    void ticketMailDrawsTheQrCodeAsATableWithNoEmbeddedPictures() throws Exception {
        User user = member(25);
        Showtime st = showtimeTomorrow();
        Booking booking = bookingService.holdSeats(user.getId(), st.getId(), freeSeats(st.getId(), 1));
        bookingService.payWithMock(booking.getCode(), user.getId());

        DemoMailbox.DemoMail mail = waitFor(m -> m.subject().contains(booking.getCode()));
        assertThat(mail.to()).isEqualTo(user.getEmail());
        assertThat(mail.html())
                .contains("Cảm ơn bạn đã đặt vé!", booking.getCode(), "bgcolor=\"#000000\"")
                .doesNotContain("cid:", "<img");
        assertThat(mail.html().length()).as("Gmail cuts mails over ~100 KB").isLessThan(60_000);
    }

    @Test
    void sampleBookingsMadeAtStartUpAreNotMailed() throws Exception {
        User user = member(40);
        Showtime st = showtimeTomorrow();
        Booking booking = bookingService.holdSeats(user.getId(), st.getId(), freeSeats(st.getId(), 1));
        bookingService.confirmPayment(booking, com.bbcinema.entity.enums.PaymentMethod.BANK_QR,
                com.bbcinema.service.BookingService.bankTransferRef(booking.getCode()), com.bbcinema.config.DataSeeder.SEED_MARK);
        Thread.sleep(1500); // the mail listener runs in the background
        assertThat(mailbox.all()).noneMatch(m -> m.subject().contains(booking.getCode()));
    }
}
