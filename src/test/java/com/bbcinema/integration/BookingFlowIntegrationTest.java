package com.bbcinema.integration;

import com.bbcinema.dto.request.CounterSaleForm;
import com.bbcinema.entity.*;
import com.bbcinema.entity.enums.*;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.exception.SeatUnavailableException;
import com.bbcinema.repository.PaymentRepository;
import com.bbcinema.service.BookingService;
import com.bbcinema.service.ShiftService;
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
    @Autowired PaymentRepository paymentRepository;
    @Autowired com.bbcinema.service.InventoryService inventoryService;

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

        // No sale before a manager opens the shift.
        form.setAmountReceived(BigDecimal.valueOf(1_000_000));
        assertThatThrownBy(() -> bookingService.sellAtCounter(staff.getId(), st.getId(), form))
                .hasMessageContaining("chưa vào ca");
        CounterShift shift = openShift(staff, 300_000);

        // Cash must cover the total; the change is recorded for the till.
        form.setAmountReceived(BigDecimal.valueOf(1_000));
        assertThatThrownBy(() -> bookingService.sellAtCounter(staff.getId(), st.getId(), form))
                .hasMessageContaining("Khách đưa thiếu");
        form.setAmountReceived(BigDecimal.valueOf(1_000_000));
        Booking sold = bookingService.sellAtCounter(staff.getId(), st.getId(), form);

        Payment cash = paymentRepository.findByTransactionRef("CASH-" + sold.getCode()).orElseThrow();
        assertThat(cash.getAmountReceived()).isEqualByComparingTo("1000000");
        assertThat(cash.getChangeAmount()).isEqualByComparingTo(BigDecimal.valueOf(1_000_000).subtract(sold.getTotalAmount()));
        ShiftService.Summary summary = shiftService.summary(shift);
        assertThat(summary.cashSales()).isEqualTo(1);
        assertThat(summary.netCash()).isEqualByComparingTo(sold.getTotalAmount());
        assertThat(summary.expectedCash()).isEqualByComparingTo(sold.getTotalAmount().add(BigDecimal.valueOf(300_000)));
        assertThat(summary.changeGiven()).isEqualByComparingTo(cash.getChangeAmount());

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

    @Test
    void staffCannotRefundAManagerApprovesAndTheShiftClosesWithItsOwnMoney() {
        User staff = account(Role.STAFF);
        User manager = account(Role.MANAGER);
        // The general manager works online only: not a till approver.
        User general = account(Role.ADMIN);
        assertThatThrownBy(() -> shiftService.open(staff.getId(), null, general.getEmail(), "Secret@123", BigDecimal.ZERO))
                .hasMessageContaining(ShiftService.WRONG_MANAGER);
        CounterShift shift = shiftService.open(staff.getId(), null, manager.getEmail(), "Secret@123", BigDecimal.valueOf(500_000));
        assertThatThrownBy(() -> shiftService.open(staff.getId(), null, manager.getEmail(), "Secret@123", BigDecimal.ZERO))
                .hasMessageContaining("ca chưa kết");
        Showtime st = showtimeTomorrow();
        CounterSaleForm form = new CounterSaleForm();
        form.setShowtimeSeatIds(freeSeats(st.getId(), 2));
        form.setAmountReceived(BigDecimal.valueOf(1_000_000));
        Booking first = bookingService.sellAtCounter(staff.getId(), st.getId(), form);
        form.setShowtimeSeatIds(freeSeats(st.getId(), 1));
        Booking second = bookingService.sellAtCounter(staff.getId(), st.getId(), form);

        // Staff only take money in.
        assertThatThrownBy(() -> bookingService.cancel(first.getCode(), staff.getId(), Role.STAFF))
                .hasMessageContaining("không được hoàn tiền");
        // A wrong or non-manager account is refused.
        assertThatThrownBy(() -> shiftService.refund(staff.getId(), first.getCode(), manager.getEmail(), "wrong"))
                .hasMessageContaining(ShiftService.WRONG_MANAGER);
        assertThatThrownBy(() -> shiftService.refund(staff.getId(), first.getCode(), staff.getEmail(), "Secret@123"))
                .hasMessageContaining(ShiftService.WRONG_MANAGER);
        Booking refunded = shiftService.refund(staff.getId(), first.getCode(), manager.getEmail(), "Secret@123");
        assertThat(refunded.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        Payment payment = paymentRepository.findByTransactionRef("CASH-" + first.getCode()).orElseThrow();
        assertThat(payment.getRefundedBy().getId()).isEqualTo(manager.getId());

        // Drawer: float 500,000 + both sales - the refund = float + the second sale.
        ShiftService.Summary summary = shiftService.summary(shift);
        assertThat(summary.cashSales()).isEqualTo(2);
        assertThat(summary.cashRefunds()).isEqualTo(1);
        assertThat(summary.expectedCash()).isEqualByComparingTo(second.getTotalAmount().add(BigDecimal.valueOf(500_000)));

        assertThatThrownBy(() -> shiftService.close(staff.getId(), manager.getEmail(), "Secret@123", null, null))
                .hasMessageContaining("đếm được");
        BigDecimal counted = summary.expectedCash().subtract(BigDecimal.valueOf(10_000));
        CounterShift closed = shiftService.close(staff.getId(), manager.getEmail(), "Secret@123", counted, "Thiếu 10k");
        assertThat(closed.isOpen()).isFalse();
        assertThat(closed.getExpectedCash()).isEqualByComparingTo(summary.expectedCash());
        assertThat(closed.getDifference()).isEqualByComparingTo("-10000");
        assertThat(shiftService.findOpen(staff.getId())).isEmpty();
        // The next shift starts from zero: earlier sales are not mixed in.
        CounterShift next = openShift(staff, 200_000);
        assertThat(shiftService.summary(next).expectedCash()).isEqualByComparingTo("200000");
    }

    @Test
    void combosTakeTheirRecipeFromTheCinemaStockAndARefundPutsItBack() {
        Showtime st = showtimeTomorrow();
        Cinema cinema = st.getRoom().getCinema();
        User manager = account(Role.MANAGER);
        manager.setCinema(cinema);
        userRepository.save(manager);
        User general = account(Role.ADMIN);
        int n = unique();
        InventoryItem corn = inventoryService.addItem("Ngô " + n, "kg");
        InventoryItem can = inventoryService.addItem("Coca " + n, "lon");
        Combo solo = combo(79_000);
        inventoryService.saveRecipe(solo.getId(), Map.of(corn.getId(), new BigDecimal("0.15"), can.getId(), BigDecimal.ONE));
        // Stock: 0.4 kg corn, 3 cans (approved). Enough for two Solo, not three.
        var note = inventoryService.request(manager.getId(), Map.of(corn.getId(), new BigDecimal("0.4"), can.getId(), new BigDecimal("3")), null);
        inventoryService.review(note.getId(), general.getId(), true, null);

        User staff = staffOnShift();
        CounterSaleForm form = new CounterSaleForm();
        form.setShowtimeSeatIds(freeSeats(st.getId(), 1));
        form.setComboQuantities(Map.of(solo.getId(), 3));
        form.setAmountReceived(BigDecimal.valueOf(1_000_000));
        assertThatThrownBy(() -> bookingService.sellAtCounter(staff.getId(), st.getId(), form))
                .hasMessageContaining("tạm hết " + corn.getName());

        form.setComboQuantities(Map.of(solo.getId(), 2));
        Booking sold = bookingService.sellAtCounter(staff.getId(), st.getId(), form);
        assertThat(onHand(cinema, corn)).isEqualByComparingTo("0.1");
        assertThat(onHand(cinema, can)).isEqualByComparingTo("1");

        // A later recipe change does not rewrite what was taken; a refund puts back exactly that.
        inventoryService.saveRecipe(solo.getId(), Map.of(corn.getId(), new BigDecimal("0.2")));
        User approver = account(Role.MANAGER);
        shiftService.refund(staff.getId(), sold.getCode(), approver.getEmail(), "Secret@123");
        assertThat(onHand(cinema, corn)).isEqualByComparingTo("0.4");
        assertThat(onHand(cinema, can)).isEqualByComparingTo("3");
    }

    private BigDecimal onHand(Cinema cinema, InventoryItem item) {
        return inventoryService.stockOf(cinema.getId()).entrySet().stream()
                .filter(e -> e.getKey().getId().equals(item.getId())).findFirst().orElseThrow().getValue();
    }
}
