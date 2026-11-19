package com.bbcinema.integration;

import com.bbcinema.dto.request.CounterSaleForm;
import com.bbcinema.entity.*;
import com.bbcinema.entity.enums.*;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.exception.SeatUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookingFlowIntegrationTest extends IntegrationTestBase {

    @Autowired TransactionTemplate tx;

    private Combo combo(long price) {
        Combo c = new Combo();
        c.setName("Combo " + unique());
        c.setPrice(BigDecimal.valueOf(price));
        return comboRepository.save(c);
    }

    private Promotion promo(int percent) {
        Promotion p = new Promotion();
        p.setCode("TEST" + unique());
        p.setDiscountPercent(percent);
        p.setValidFrom(LocalDate.now().minusDays(1));
        p.setValidTo(LocalDate.now().plusDays(1));
        p.setUsageLimit(5);
        return promotionRepository.save(p);
    }

    @Test
    void fullOnlineBookingAndCancellation() {
        User user = member(25);
        user.setPoints(100);
        userRepository.save(user);
        Showtime st = showtimeTomorrow();
        Combo combo = combo(79_000);
        Promotion promotion = promo(10);

        Booking booking = bookingService.holdSeats(user.getId(), st.getId(), freeSeats(st.getId(), 2));
        String code = booking.getCode();
        assertThat(booking.getTicketAmount()).isEqualByComparingTo("120000");

        bookingService.updateCombos(code, user.getId(), Map.of(combo.getId(), 2));
        bookingService.applyPromotion(code, user.getId(), promotion.getCode().toLowerCase());
        bookingService.usePoints(code, user.getId(), 20);
        booking = bookingService.payWithMock(code, user.getId());

        // 120,000 + 158,000 = 278,000; -10% = 27,000 (rounded down); -20 points = 20,000
        assertThat(booking.getPromotionDiscount()).isEqualByComparingTo("27000");
        assertThat(booking.getTotalAmount()).isEqualByComparingTo("231000");
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PAID);
        assertThat(booking.getPointsEarned()).isEqualTo(11);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getPoints()).isEqualTo(100 - 20 + 11);
        assertThat(promotionRepository.findById(promotion.getId()).orElseThrow().getUsedCount()).isEqualTo(1);
        assertThat(showtimeSeatRepository.countByShowtimeIdAndStatus(st.getId(), SeatStatus.BOOKED)).isEqualTo(2);

        bookingService.cancel(code, user.getId(), Role.USER);

        assertThat(bookingRepository.findDetailedByCode(code).orElseThrow().getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(userRepository.findById(user.getId()).orElseThrow().getPoints()).isEqualTo(100);
        assertThat(promotionRepository.findById(promotion.getId()).orElseThrow().getUsedCount()).isZero();
        assertThat(showtimeSeatRepository.countByShowtimeIdAndStatus(st.getId(), SeatStatus.BOOKED)).isZero();
    }

    @Test
    void onlyOneOfManyConcurrentBuyersGetsTheSeat() throws Exception {
        Showtime st = showtimeTomorrow();
        Long seat = freeSeats(st.getId(), 1).get(0);
        List<User> buyers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            buyers.add(member(30));
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger won = new AtomicInteger();
        AtomicInteger lost = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (User buyer : buyers) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    bookingService.holdSeats(buyer.getId(), st.getId(), List.of(seat));
                    won.incrementAndGet();
                } catch (SeatUnavailableException e) {
                    lost.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(won.get()).isEqualTo(1);
        assertThat(lost.get()).isEqualTo(7);
        assertThat(showtimeSeatRepository.countByShowtimeIdAndStatus(st.getId(), SeatStatus.HELD)).isEqualTo(1);
    }

    @Test
    void unpaidHoldIsReleasedWhenTimeRunsOut() {
        User user = member(25);
        Showtime st = showtimeTomorrow();
        Booking booking = bookingService.holdSeats(user.getId(), st.getId(), freeSeats(st.getId(), 3));
        tx.executeWithoutResult(s -> bookingRepository.findById(booking.getId()).orElseThrow()
                .setExpiresAt(LocalDateTime.now().minusSeconds(1)));

        assertThat(bookingService.expireOverdueBookings()).isGreaterThanOrEqualTo(1);

        assertThat(bookingRepository.findById(booking.getId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(showtimeSeatRepository.countByShowtimeIdAndStatus(st.getId(), SeatStatus.HELD)).isZero();
        assertThatThrownBy(() -> bookingService.payWithMock(booking.getCode(), user.getId())).isInstanceOf(BusinessException.class);
    }

    @Test
    void underageMemberCannotBookRestrictedMovie() {
        User teen = member(14);
        Showtime st = showtime(AgeRating.T16, LocalDateTime.now().plusDays(2).withHour(20).withMinute(0));
        assertThatThrownBy(() -> bookingService.holdSeats(teen.getId(), st.getId(), freeSeats(st.getId(), 1)))
                .hasMessageContaining("16 tuổi");
    }

    @Test
    void counterSaleThenCheckIn() {
        User staff = account(Role.STAFF);
        LocalDateTime soon = LocalDateTime.now().plusMinutes(30).withSecond(0).withNano(0);
        Showtime st = showtime(AgeRating.P, soon);
        Combo combo = combo(45_000);
        CounterSaleForm form = new CounterSaleForm();
        form.setShowtimeSeatIds(freeSeats(st.getId(), 2));
        form.setComboQuantities(Map.of(combo.getId(), 1));
        form.setCustomerName("Khách lẻ");

        Booking sold = bookingService.sellAtCounter(staff.getId(), st.getId(), form);

        assertThat(sold.getStatus()).isEqualTo(BookingStatus.PAID);
        assertThat(sold.getChannel()).isEqualTo(SalesChannel.COUNTER);
        assertThat(sold.getTotalAmount()).isEqualByComparingTo(sold.getTicketAmount().add(BigDecimal.valueOf(45_000)));
        Booking checked = bookingService.checkIn(sold.getCode().toLowerCase());
        assertThat(checked.getCheckedInAt()).isNotNull();
        assertThatThrownBy(() -> bookingService.checkIn(sold.getCode())).hasMessageContaining("đã được soát");
    }

    @Test
    void showtimesInOneRoomCannotOverlap() {
        Showtime first = showtimeTomorrow();
        assertThatThrownBy(() -> showtimeService.create(first.getMovie(), first.getRoom(),
                first.getStartTime().plusMinutes(60), ScreenFormat.TWO_D, false))
                .hasMessageContaining("trùng giờ");
        // Starting after the previous show plus cleaning time is fine.
        Showtime next = showtimeService.create(first.getMovie(), first.getRoom(), first.getEndTime(), ScreenFormat.TWO_D, false);
        assertThat(next.getId()).isNotNull();
    }

    @Test
    void goldMemberGetsTierDiscountBeforePromotionAndPoints() {
        User user = member(28);
        // 3,000,000đ spent this year makes the member Gold (10% off).
        Showtime first = showtimeTomorrow();
        Booking past = bookingService.holdSeats(user.getId(), first.getId(), freeSeats(first.getId(), 1));
        bookingService.payWithMock(past.getCode(), user.getId());
        Booking saved = bookingRepository.findDetailedByCode(past.getCode()).orElseThrow();
        saved.setTotalAmount(java.math.BigDecimal.valueOf(3_000_000));
        bookingRepository.save(saved);

        Showtime st = showtimeTomorrow();
        Booking booking = bookingService.holdSeats(user.getId(), st.getId(), freeSeats(st.getId(), 2));

        assertThat(booking.getMemberTier()).isEqualTo(com.bbcinema.entity.enums.MemberTier.GOLD);
        assertThat(booking.getTierDiscount()).isEqualByComparingTo(booking.getTicketAmount().multiply(java.math.BigDecimal.valueOf(0.1)));
        assertThat(booking.getTotalAmount()).isEqualByComparingTo(booking.getTicketAmount().subtract(booking.getTierDiscount()));
    }
}
