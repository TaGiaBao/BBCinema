package com.bbcinema.service;

import com.bbcinema.TestFixtures;
import com.bbcinema.entity.*;
import com.bbcinema.entity.enums.*;
import com.bbcinema.exception.AccessDeniedBookingException;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.exception.SeatUnavailableException;
import com.bbcinema.repository.BookingRepository;
import com.bbcinema.repository.PaymentRepository;
import com.bbcinema.repository.ShowtimeSeatRepository;
import com.bbcinema.service.event.BookingPaidEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock BookingRepository bookingRepository;
    @Mock ShowtimeSeatRepository showtimeSeatRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock ShowtimeService showtimeService;
    @Mock UserService userService;
    @Mock ComboService comboService;
    @Mock PromotionService promotionService;
    @Mock ApplicationEventPublisher eventPublisher;

    BookingService service;
    PointService pointService;
    User user;
    Showtime showtime;
    ShowtimeSeat a1;
    ShowtimeSeat a2;

    @BeforeEach
    void setUp() {
        pointService = spy(new PointService(mock(com.bbcinema.repository.PointTransactionRepository.class),
                TestFixtures.properties(), TestFixtures.clock()));
        service = new BookingService(bookingRepository, showtimeSeatRepository, paymentRepository, showtimeService,
                userService, comboService, promotionService, pointService,
                new MembershipService(bookingRepository, TestFixtures.properties(), TestFixtures.clock()), eventPublisher,
                TestFixtures.properties(), TestFixtures.clock());
        user = TestFixtures.user(1, LocalDate.of(2000, 1, 1));
        showtime = TestFixtures.showtime(10, TestFixtures.movie(1, 120, AgeRating.T16), TestFixtures.room(1),
                TestFixtures.NOW.plusDays(1).withHour(19));
        a1 = TestFixtures.seat(101, showtime, "A", 1, 60_000);
        a2 = TestFixtures.seat(102, showtime, "A", 2, 60_000);
    }

    private void stubHold() {
        when(userService.getById(1L)).thenReturn(user);
        when(showtimeService.getDetailed(10L)).thenReturn(showtime);
    }

    @Test
    void holdsFreeSeatsForFiveMinutes() {
        stubHold();
        when(bookingRepository.save(any())).thenAnswer(inv -> { Booking b = inv.getArgument(0); b.setId(55L); return b; });
        when(showtimeSeatRepository.findForUpdate(eq(10L), anyCollection())).thenReturn(List.of(a1, a2));

        Booking booking = service.holdSeats(1L, 10L, List.of(101L, 102L, 101L));

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(booking.getExpiresAt()).isEqualTo(TestFixtures.NOW.plusMinutes(5));
        assertThat(booking.getTicketAmount()).isEqualByComparingTo("120000");
        assertThat(booking.getTotalAmount()).isEqualByComparingTo("120000");
        assertThat(a1.getStatus()).isEqualTo(SeatStatus.HELD);
        assertThat(a1.getBooking()).isSameAs(booking);
        assertThat(booking.getCode()).hasSize(8);
    }

    @Test
    void refusesSeatsSomeoneElseHolds() {
        stubHold();
        when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        a2.setStatus(SeatStatus.HELD);
        a2.setHoldExpiresAt(TestFixtures.NOW.plusMinutes(3));
        when(showtimeSeatRepository.findForUpdate(eq(10L), anyCollection())).thenReturn(List.of(a1, a2));

        assertThatThrownBy(() -> service.holdSeats(1L, 10L, List.of(101L, 102L)))
                .isInstanceOf(SeatUnavailableException.class)
                .hasMessageContaining("A2");
        assertThat(a1.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
    }

    @Test
    void takesOverSeatWhoseHoldExpired() {
        stubHold();
        when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        a1.setStatus(SeatStatus.HELD);
        a1.setHoldExpiresAt(TestFixtures.NOW.minusMinutes(1));
        when(showtimeSeatRepository.findForUpdate(eq(10L), anyCollection())).thenReturn(List.of(a1));

        Booking booking = service.holdSeats(1L, 10L, List.of(101L));
        assertThat(a1.getBooking()).isSameAs(booking);
    }

    @Test
    void refusesEmptySelectionAndUnknownSeats() {
        stubHold();
        when(bookingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        assertThatThrownBy(() -> service.holdSeats(1L, 10L, List.of())).hasMessageContaining("ít nhất một ghế");
        when(showtimeSeatRepository.findForUpdate(eq(10L), anyCollection())).thenReturn(List.of(a1));
        assertThatThrownBy(() -> service.holdSeats(1L, 10L, List.of(101L, 999L))).hasMessageContaining("không thuộc");
    }

    @Test
    void refusesStartedOrCancelledShowtime() {
        stubHold();
        showtime.setStartTime(TestFixtures.NOW.minusMinutes(1));
        assertThatThrownBy(() -> service.holdSeats(1L, 10L, List.of(101L))).hasMessageContaining("đã bắt đầu");
        showtime.setStartTime(TestFixtures.NOW.plusDays(1));
        showtime.setCancelled(true);
        assertThatThrownBy(() -> service.holdSeats(1L, 10L, List.of(101L))).hasMessageContaining("bị huỷ");
    }

    @Test
    void checksAgeAgainstRating() {
        Movie t16 = TestFixtures.movie(1, 100, AgeRating.T16);
        LocalDate today = TestFixtures.NOW.toLocalDate();
        assertThatThrownBy(() -> BookingService.checkAge(TestFixtures.user(1, today.minusYears(15)), t16, today))
                .hasMessageContaining("15 tuổi");
        assertThatThrownBy(() -> BookingService.checkAge(TestFixtures.user(1, null), t16, today))
                .hasMessageContaining("cập nhật ngày sinh");
        BookingService.checkAge(TestFixtures.user(1, today.minusYears(16)), t16, today);
        BookingService.checkAge(TestFixtures.user(1, null), TestFixtures.movie(2, 90, AgeRating.P), today);
    }

    private Booking pendingBooking() {
        Booking b = new Booking();
        b.setId(55L);
        b.setCode("TESTCODE");
        b.setUser(user);
        b.setShowtime(showtime);
        b.setStatus(BookingStatus.PENDING);
        b.setExpiresAt(TestFixtures.NOW.plusMinutes(4));
        b.setTicketAmount(BigDecimal.valueOf(120_000));
        a1.hold(b, b.getExpiresAt());
        a2.hold(b, b.getExpiresAt());
        b.getSeats().addAll(List.of(a1, a2));
        return b;
    }

    @Test
    void recalculateAppliesPromotionThenPoints() {
        Booking b = pendingBooking();
        b.setComboAmount(BigDecimal.valueOf(80_000));
        b.setPromotion(TestFixtures.promotion("P10", 10, 30_000L, null,
                TestFixtures.NOW.toLocalDate(), TestFixtures.NOW.toLocalDate(), null));
        user.setPoints(500);
        b.setPointsUsed(500);

        service.recalculate(b);

        // subtotal 200,000 -> promo 20,000 -> points capped at 50% of 180,000 = 90 points
        assertThat(b.getPromotionDiscount()).isEqualByComparingTo("20000");
        assertThat(b.getPointsUsed()).isEqualTo(90);
        assertThat(b.getTotalAmount()).isEqualByComparingTo("90000");
    }

    @Test
    void recalculateDropsPromotionThatNoLongerApplies() {
        Booking b = pendingBooking();
        b.setPromotion(TestFixtures.promotion("MIN", 10, null, 500_000L,
                TestFixtures.NOW.toLocalDate(), TestFixtures.NOW.toLocalDate(), null));
        service.recalculate(b);
        assertThat(b.getPromotion()).isNull();
        assertThat(b.getTotalAmount()).isEqualByComparingTo("120000");
    }

    @Test
    void paymentBooksSeatsEarnsPointsAndPublishesEvent() {
        Booking b = pendingBooking();
        b.setTotalAmount(BigDecimal.valueOf(120_000));
        when(showtimeSeatRepository.findForUpdate(eq(10L), anyCollection())).thenReturn(List.of(a1, a2));
        when(paymentRepository.findByTransactionRef(any())).thenReturn(Optional.empty());

        service.confirmPayment(b, PaymentMethod.MOCK, "MOCK-TESTCODE", null);

        assertThat(b.getStatus()).isEqualTo(BookingStatus.PAID);
        assertThat(a1.getStatus()).isEqualTo(SeatStatus.BOOKED);
        assertThat(b.getPointsEarned()).isEqualTo(6);
        assertThat(user.getPoints()).isEqualTo(6);
        verify(eventPublisher).publishEvent(new BookingPaidEvent("TESTCODE"));
        verify(paymentRepository).save(argThat(p -> p.getStatus() == PaymentStatus.SUCCESS && p.getAmount().intValue() == 120_000));
    }

    @Test
    void paymentFailsWhenSeatsWereReleased() {
        Booking b = pendingBooking();
        a2.release();
        when(showtimeSeatRepository.findForUpdate(eq(10L), anyCollection())).thenReturn(List.of(a1, a2));

        assertThatThrownBy(() -> service.confirmPayment(b, PaymentMethod.MOCK, "X", null)).hasMessageContaining("giải phóng");
        assertThat(b.getStatus()).isEqualTo(BookingStatus.PENDING);
    }

    @Test
    void paymentIsIdempotent() {
        Booking b = pendingBooking();
        b.setStatus(BookingStatus.PAID);
        assertThat(service.confirmPayment(b, PaymentMethod.MOCK, "X", null)).isSameAs(b);
        verifyNoInteractions(showtimeSeatRepository, eventPublisher);
    }

    @Test
    void memberCannotCancelCloseToShowtime() {
        Booking b = pendingBooking();
        b.setStatus(BookingStatus.PAID);
        showtime.setStartTime(TestFixtures.NOW.plusMinutes(90));
        when(bookingRepository.findDetailedByCode("TESTCODE")).thenReturn(Optional.of(b));

        assertThat(service.canCancel(b)).isFalse();
        assertThatThrownBy(() -> service.cancel("TESTCODE", 1L, Role.USER)).hasMessageContaining("2 tiếng");
    }

    @Test
    void cancellingPaidBookingRevertsPointsAndPromotion() {
        Booking b = pendingBooking();
        b.setStatus(BookingStatus.PAID);
        b.setPointsUsed(10);
        b.setPointsEarned(6);
        Promotion promo = TestFixtures.promotion("P", 10, null, null, TestFixtures.NOW.toLocalDate(), TestFixtures.NOW.toLocalDate(), null);
        promo.setUsedCount(3);
        b.setPromotion(promo);
        user.setPoints(20);
        a1.markBooked();
        a2.markBooked();
        when(bookingRepository.findDetailedByCode("TESTCODE")).thenReturn(Optional.of(b));
        when(paymentRepository.findByBookingIdOrderByCreatedAtDesc(55L)).thenReturn(List.of());

        service.cancel("TESTCODE", 1L, Role.USER);

        assertThat(b.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(user.getPoints()).isEqualTo(20 + 10 - 6);
        assertThat(promo.getUsedCount()).isEqualTo(2);
        assertThat(a1.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(a1.getBooking()).isNull();
    }

    @Test
    void otherMembersCannotSeeBooking() {
        Booking b = pendingBooking();
        when(bookingRepository.findDetailedByCode("TESTCODE")).thenReturn(Optional.of(b));
        assertThatThrownBy(() -> service.getForViewer("TESTCODE", 2L, Role.USER)).isInstanceOf(AccessDeniedBookingException.class);
        assertThat(service.getForViewer("TESTCODE", 2L, Role.STAFF)).isSameAs(b);
    }

    @Test
    void expireJobReleasesOnlyOwnSeats() {
        Booking b = pendingBooking();
        Booking other = new Booking();
        other.setId(77L);
        a2.hold(other, TestFixtures.NOW.plusMinutes(5));
        when(bookingRepository.findByStatusAndExpiresAtBefore(BookingStatus.PENDING, TestFixtures.NOW)).thenReturn(List.of(b));

        assertThat(service.expireOverdueBookings()).isEqualTo(1);
        assertThat(b.getStatus()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(a1.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(a2.getStatus()).isEqualTo(SeatStatus.HELD);
    }

    @Test
    void checkInWindowAndDoubleCheckIn() {
        Booking b = pendingBooking();
        b.setStatus(BookingStatus.PAID);
        when(bookingRepository.findDetailedByCode("TESTCODE")).thenReturn(Optional.of(b));
        assertThatThrownBy(() -> service.checkIn("testcode")).hasMessageContaining("Chưa đến giờ");

        showtime.setStartTime(TestFixtures.NOW.plusMinutes(30));
        showtime.setEndTime(TestFixtures.NOW.plusMinutes(165));
        service.checkIn("testcode");
        assertThat(b.getCheckedInAt()).isEqualTo(TestFixtures.NOW);
        assertThatThrownBy(() -> service.checkIn("TESTCODE")).hasMessageContaining("đã được soát");
    }

    @Test
    void usePointsValidatesLimit() {
        Booking b = pendingBooking();
        user.setPoints(100);
        when(bookingRepository.findDetailedByCode("TESTCODE")).thenReturn(Optional.of(b));
        assertThatThrownBy(() -> service.usePoints("TESTCODE", 1L, 61)).hasMessageContaining("tối đa 60");
        service.usePoints("TESTCODE", 1L, 60);
        assertThat(b.getTotalAmount()).isEqualByComparingTo("60000");
    }

    @Test
    void cannotChangeExpiredBooking() {
        Booking b = pendingBooking();
        b.setExpiresAt(TestFixtures.NOW.minusSeconds(1));
        when(bookingRepository.findDetailedByCode("TESTCODE")).thenReturn(Optional.of(b));
        assertThatThrownBy(() -> service.removePromotion("TESTCODE", 1L)).isInstanceOf(BusinessException.class).hasMessageContaining("hết thời gian");
    }

    @Test
    void cancelDeadlineIsTwoHoursBeforeStart() {
        Booking b = pendingBooking();
        assertThat(service.cancelDeadline(b)).isEqualTo(showtime.getStartTime().minusHours(2));
        assertThat(LocalDateTime.of(2026, 11, 20, 17, 0)).isEqualTo(service.cancelDeadline(b));
    }

    @Test
    void paymentArrivingAfterHoldTimeIsRefusedEvenBeforeCleanupJob() {
        Booking b = pendingBooking();
        // Hold ended a second ago; the cleanup job has not run, so the seats are still HELD.
        b.setExpiresAt(TestFixtures.NOW.minusSeconds(1));
        assertThat(a1.getStatus()).isEqualTo(SeatStatus.HELD);

        assertThatThrownBy(() -> service.confirmPayment(b, PaymentMethod.VNPAY, "LATE-TXN", "777"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("hết thời gian giữ ghế");
        assertThat(b.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(a1.getStatus()).isEqualTo(SeatStatus.HELD);
        verifyNoInteractions(eventPublisher);
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void holdExpiryUsesTheClock() {
        Booking b = pendingBooking();
        assertThat(service.isHoldExpired(b)).isFalse();
        b.setExpiresAt(TestFixtures.NOW.minusSeconds(1));
        assertThat(service.isHoldExpired(b)).isTrue();
        b.setStatus(BookingStatus.PAID);
        assertThat(service.isHoldExpired(b)).isFalse();
    }

    @Test
    void cashAtTheCounterMustCoverTheTotalAndGivesChange() {
        BigDecimal total = BigDecimal.valueOf(165_000);
        assertThat(BookingService.counterChange(PaymentMethod.CASH, BigDecimal.valueOf(200_000), total)).isEqualByComparingTo("35000");
        assertThat(BookingService.counterChange(PaymentMethod.CASH, total, total)).isEqualByComparingTo("0");
        assertThatThrownBy(() -> BookingService.counterChange(PaymentMethod.CASH, BigDecimal.valueOf(150_000), total))
                .hasMessageContaining("Khách đưa thiếu 15.000đ");
        assertThatThrownBy(() -> BookingService.counterChange(PaymentMethod.CASH, null, total))
                .hasMessageContaining("Nhập số tiền khách đưa");
    }

    @Test
    void cardSlipAtTheCounterMustMatchTheTotal() {
        BigDecimal total = BigDecimal.valueOf(165_000);
        assertThat(BookingService.counterChange(PaymentMethod.CARD, total, total)).isEqualByComparingTo("0");
        assertThatThrownBy(() -> BookingService.counterChange(PaymentMethod.CARD, BigDecimal.valueOf(170_000), total))
                .hasMessageContaining("Hoá đơn POS thừa 5.000đ");
        assertThatThrownBy(() -> BookingService.counterChange(PaymentMethod.CARD, BigDecimal.valueOf(160_000), total))
                .hasMessageContaining("Hoá đơn POS thiếu 5.000đ");
        assertThatThrownBy(() -> BookingService.counterChange(PaymentMethod.CARD, null, total))
                .hasMessageContaining("hoá đơn máy POS");
    }
}
