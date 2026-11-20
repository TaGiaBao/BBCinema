package com.bbcinema.integration;

import com.bbcinema.entity.Booking;
import com.bbcinema.entity.Showtime;
import com.bbcinema.entity.User;
import com.bbcinema.entity.enums.AgeRating;
import com.bbcinema.entity.enums.BookingStatus;
import com.bbcinema.entity.enums.Role;
import jakarta.servlet.http.Cookie;
import com.bbcinema.service.TicketPrintService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Printing paper tickets of online bookings from the check-in page, the print log and the age reminder. */
class TicketPrintIntegrationTest extends IntegrationTestBase {

    @Autowired TicketPrintService ticketPrintService;
    @Autowired PlatformTransactionManager transactionManager;

    private static final String AGE_WARNING_T16 = "Phim T16: kiểm tra căn cước nếu khách trông chưa đủ 16 tuổi.";

    private Showtime showtimeTomorrow(AgeRating rating) {
        return showtime(rating, LocalDateTime.now().plusDays(1).withHour(19).withMinute(0).withSecond(0).withNano(0));
    }

    private Booking paidOnline(Showtime st) {
        User member = member(25);
        Booking booking = bookingService.holdSeats(member.getId(), st.getId(), freeSeats(st.getId(), 1));
        return bookingService.payWithMock(booking.getCode(), member.getId());
    }

    private int printCountOf(Booking booking) {
        return bookingRepository.findDetailedByCode(booking.getCode()).orElseThrow().getPrintCount();
    }

    @Test
    void checkInPageOffersPrintAndAgeReminderForPaidOnlineBooking() throws Exception {
        Booking booking = paidOnline(showtimeTomorrow(AgeRating.T16));
        Cookie staff = authCookie(staffOnShift());

        mvc.perform(get("/staff/checkin").param("code", booking.getCode()).cookie(staff))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/staff/tickets/" + booking.getCode() + "?from=checkin\"")))
                // Same tab: coming back to check-in reloads the print status.
                .andExpect(content().string(not(containsString("target=\"_blank\""))))
                .andExpect(content().string(containsString("data-role=\"print-link\"")))
                .andExpect(content().string(containsString("Chưa in vé giấy")))
                .andExpect(content().string(containsString("class=\"age-badge T16\"")))
                .andExpect(content().string(containsString(AGE_WARNING_T16)))
                // The reminder never blocks: the confirm button is still there.
                .andExpect(content().string(containsString("Xác nhận vào phòng")));
    }

    @Test
    void ageReminderShowsFromT13AndNotForAllAges() throws Exception {
        Cookie staff = authCookie(staffOnShift());
        Booking t13 = paidOnline(showtimeTomorrow(AgeRating.T13));
        mvc.perform(get("/staff/checkin").param("code", t13.getCode()).cookie(staff))
                .andExpect(content().string(containsString("Phim T13: kiểm tra căn cước nếu khách trông chưa đủ 13 tuổi.")));

        Booking p = paidOnline(showtimeTomorrow(AgeRating.P));
        mvc.perform(get("/staff/checkin").param("code", p.getCode()).cookie(staff))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"age-badge P\"")))
                .andExpect(content().string(not(containsString("data-role=\"age-warning\""))))
                .andExpect(content().string(not(containsString("kiểm tra căn cước"))));
    }

    @Test
    void unpaidBookingHasNoPrintButton() throws Exception {
        Showtime st = showtimeTomorrow(AgeRating.P);
        User member = member(25);
        Booking pending = bookingService.holdSeats(member.getId(), st.getId(), freeSeats(st.getId(), 1));
        Cookie staff = authCookie(staffOnShift());

        mvc.perform(get("/staff/checkin").param("code", pending.getCode()).cookie(staff))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("data-role=\"print-link\""))))
                .andExpect(content().string(not(containsString("Chưa in vé giấy"))));
        mvc.perform(get("/staff/tickets/" + pending.getCode()).cookie(staff))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("data-role=\"print-ticket\""))))
                .andExpect(content().string(containsString("không in vé được")));
    }

    @Test
    void printButtonLogsEachPrintAndTheCountIsShown() throws Exception {
        Booking booking = paidOnline(showtimeTomorrow(AgeRating.P));
        Cookie staff = authCookie(staffOnShift());

        // The ticket page of an online booking prints fine: no cash fields, button wired to the print log.
        mvc.perform(get("/staff/tickets/" + booking.getCode()).cookie(staff))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-print-url=\"/staff/tickets/" + booking.getCode() + "/printed\"")))
                .andExpect(content().string(containsString("/js/ticket-print.js")))
                .andExpect(content().string(containsString("Thanh toán online lúc")))
                .andExpect(content().string(not(containsString(" · Online"))))
                .andExpect(content().string(containsString("data-role=\"print-warning\"")))
                .andExpect(content().string(not(containsString("Khách đưa"))))
                .andExpect(content().string(not(containsString("onclick=\"window.print()\""))));
        assertThat(printCountOf(booking)).isZero(); // opening the page is not a print

        mvc.perform(post("/staff/tickets/" + booking.getCode() + "/printed").with(csrf()).cookie(staff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.printCount").value(1))
                .andExpect(jsonPath("$.lastPrintedAt").isString());
        mvc.perform(post("/staff/tickets/" + booking.getCode().toLowerCase() + "/printed").with(csrf()).cookie(staff))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.printCount").value(2));

        Booking stored = bookingRepository.findDetailedByCode(booking.getCode()).orElseThrow();
        assertThat(stored.getPrintCount()).isEqualTo(2);
        assertThat(stored.getLastPrintedAt()).isNotNull();

        mvc.perform(get("/staff/checkin").param("code", booking.getCode()).cookie(staff))
                .andExpect(content().string(containsString("Đã in 2 lần · lần cuối ")))
                .andExpect(content().string(not(containsString("Chưa in vé giấy"))));
        mvc.perform(get("/staff/tickets/" + booking.getCode()).cookie(staff))
                .andExpect(content().string(containsString("Đã in 2 lần · lần cuối ")));
    }

    @Test
    void unpaidOrCancelledBookingsCannotBeLogged() throws Exception {
        Showtime st = showtimeTomorrow(AgeRating.P);
        User member = member(25);
        Booking pending = bookingService.holdSeats(member.getId(), st.getId(), freeSeats(st.getId(), 1));
        Booking cancelled = paidOnline(st);
        Booking stored = bookingRepository.findDetailedByCode(cancelled.getCode()).orElseThrow();
        stored.setStatus(BookingStatus.CANCELLED);
        bookingRepository.save(stored);
        Cookie staff = authCookie(staffOnShift());

        for (Booking b : new Booking[]{pending, cancelled}) {
            mvc.perform(post("/staff/tickets/" + b.getCode() + "/printed").with(csrf()).cookie(staff))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message", containsString("không thể in vé")));
            assertThat(printCountOf(b)).isZero();
        }
        mvc.perform(post("/staff/tickets/NOPE0000/printed").with(csrf()).cookie(staff))
                .andExpect(status().isNotFound());
    }

    @Test
    void printLogNeedsCounterRoleAndCsrf() throws Exception {
        Booking booking = paidOnline(showtimeTomorrow(AgeRating.P));
        String url = "/staff/tickets/" + booking.getCode() + "/printed";

        mvc.perform(post(url).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/internal/login*"));
        mvc.perform(post(url).with(csrf()).cookie(authCookie(member(30))))
                .andExpect(status().isForbidden());
        mvc.perform(post(url).with(csrf()).cookie(authCookie(account(Role.ADMIN))))
                .andExpect(status().isForbidden());
        mvc.perform(post(url).cookie(authCookie(staffOnShift())))
                .andExpect(status().isForbidden());
        assertThat(printCountOf(booking)).isZero();

        mvc.perform(post(url).with(csrf()).cookie(authCookie(account(Role.MANAGER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.printCount").value(1));
    }

    @Test
    void kidsRatingShowsCompanionReminderOnly() throws Exception {
        Cookie staff = authCookie(staffOnShift());
        Booking k = paidOnline(showtimeTomorrow(AgeRating.K));
        mvc.perform(get("/staff/checkin").param("code", k.getCode()).cookie(staff))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Phim K: trẻ dưới 13 tuổi cần người lớn đi kèm.")))
                .andExpect(content().string(not(containsString("kiểm tra căn cước"))))
                .andExpect(content().string(containsString("Xác nhận vào phòng")));

        Booking p = paidOnline(showtimeTomorrow(AgeRating.P));
        mvc.perform(get("/staff/checkin").param("code", p.getCode()).cookie(staff))
                .andExpect(content().string(not(containsString("Phim K:"))));
    }

    @Test
    void backToCheckInLinkOnlyWhenComingFromCheckIn() throws Exception {
        Booking booking = paidOnline(showtimeTomorrow(AgeRating.P));
        Cookie staff = authCookie(staffOnShift());
        String back = "href=\"/staff/checkin?code=" + booking.getCode() + "\"";

        mvc.perform(get("/staff/tickets/" + booking.getCode()).param("from", "checkin").cookie(staff))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Quay lại soát vé")))
                .andExpect(content().string(containsString(back)));
        mvc.perform(get("/staff/tickets/" + booking.getCode()).cookie(staff))
                .andExpect(content().string(not(containsString("Quay lại soát vé"))));
        // Anything else is not a destination, just "no flag".
        mvc.perform(get("/staff/tickets/" + booking.getCode()).param("from", "https://evil.example.com").cookie(staff))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Quay lại soát vé"))))
                .andExpect(content().string(not(containsString("evil.example.com"))));
    }

    @Test
    void staffWithoutOpenShiftIsSentToOpenShiftAndNothingIsCounted() throws Exception {
        Booking booking = paidOnline(showtimeTomorrow(AgeRating.P));

        mvc.perform(post("/staff/tickets/" + booking.getCode() + "/printed").with(csrf()).cookie(authCookie(account(Role.STAFF))))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/staff/shift/open*"));
        assertThat(printCountOf(booking)).isZero();
    }

    @Test
    void printDoesNotBumpVersionSoAConcurrentCheckInStillSucceeds() throws Exception {
        Booking booking = paidOnline(showtime(AgeRating.P, LocalDateTime.now().plusMinutes(30).withSecond(0).withNano(0)));
        Long versionBefore = bookingRepository.findDetailedByCode(booking.getCode()).orElseThrow().getVersion();

        ticketPrintService.recordPrint(booking.getCode());
        assertThat(bookingRepository.findDetailedByCode(booking.getCode()).orElseThrow().getVersion()).isEqualTo(versionBefore);

        // Check-in loads the booking, a print commits in another transaction, then check-in commits.
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                Booking checked = bookingService.checkIn(booking.getCode());
                assertThat(checked.getCheckedInAt()).isNotNull();
                try {
                    other.submit(() -> ticketPrintService.recordPrint(booking.getCode())).get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        } finally {
            other.shutdownNow();
        }

        Booking stored = bookingRepository.findDetailedByCode(booking.getCode()).orElseThrow();
        assertThat(stored.getCheckedInAt()).isNotNull();
        // The check-in did not write its stale count back over the print made meanwhile.
        assertThat(stored.getPrintCount()).isEqualTo(2);
    }

    @Test
    void twoPrintsAtTheSameMomentAreBothCounted() throws Exception {
        Booking booking = paidOnline(showtimeTomorrow(AgeRating.P));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<TicketPrintService.PrintLog> a = pool.submit(() -> { start.await(); return ticketPrintService.recordPrint(booking.getCode()); });
            Future<TicketPrintService.PrintLog> b = pool.submit(() -> { start.await(); return ticketPrintService.recordPrint(booking.getCode()); });
            start.countDown();
            assertThat(java.util.List.of(a.get(10, TimeUnit.SECONDS).printCount(), b.get(10, TimeUnit.SECONDS).printCount()))
                    .containsExactlyInAnyOrder(1, 2);
        } finally {
            pool.shutdownNow();
        }
        assertThat(printCountOf(booking)).isEqualTo(2);
    }
}
