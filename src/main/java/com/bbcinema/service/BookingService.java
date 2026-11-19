package com.bbcinema.service;

import com.bbcinema.config.AppProperties;
import com.bbcinema.dto.request.CounterSaleForm;
import com.bbcinema.entity.*;
import com.bbcinema.entity.enums.*;
import com.bbcinema.exception.AccessDeniedBookingException;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.exception.NotFoundException;
import com.bbcinema.exception.SeatUnavailableException;
import com.bbcinema.repository.BookingRepository;
import com.bbcinema.repository.PaymentRepository;
import com.bbcinema.repository.ShowtimeSeatRepository;
import com.bbcinema.service.event.BookingPaidEvent;
import com.bbcinema.util.CodeGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * The booking life cycle: hold seats -> choose combos -> apply discounts -> pay -> (cancel | check in).
 * Holding is the critical step: seats are loaded with a row lock so two people can never hold the
 * same seat, and a hold that is not paid in time is released by {@link #expireOverdueBookings()}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BookingService {

    private static final int MAX_COMBO_QUANTITY = 20;

    private final BookingRepository bookingRepository;
    private final ShowtimeSeatRepository showtimeSeatRepository;
    private final PaymentRepository paymentRepository;
    private final ShowtimeService showtimeService;
    private final UserService userService;
    private final ComboService comboService;
    private final PromotionService promotionService;
    private final PointService pointService;
    private final ApplicationEventPublisher eventPublisher;
    private final AppProperties properties;
    private final Clock clock;

    // ------------------------------------------------------------------ queries

    public Booking getByCode(String code) {
        return bookingRepository.findDetailedByCode(code)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy đơn đặt vé " + code));
    }

    /** Loads a booking for a viewer. Members only see their own bookings; staff and admins see all. */
    public Booking getForViewer(String code, Long viewerId, Role viewerRole) {
        Booking booking = getByCode(code);
        checkOwner(booking, viewerId, viewerRole);
        return booking;
    }

    public Page<Booking> history(Long userId, Pageable pageable) {
        return bookingRepository.findHistory(userId, pageable);
    }

    public Page<Booking> search(BookingStatus status, String keyword, Pageable pageable) {
        String trimmed = keyword == null || keyword.isBlank() ? null : keyword.trim();
        return bookingRepository.search(status, trimmed, pageable);
    }

    public List<Payment> payments(Long bookingId) {
        return paymentRepository.findByBookingIdOrderByCreatedAtDesc(bookingId);
    }

    /** Whether a paid booking can still be cancelled by its owner. */
    public boolean canCancel(Booking booking) {
        if (booking.isPending()) {
            return true;
        }
        return booking.isPaid() && booking.getCheckedInAt() == null
                && !LocalDateTime.now(clock).isAfter(cancelDeadline(booking));
    }

    public LocalDateTime cancelDeadline(Booking booking) {
        return booking.getShowtime().getStartTime().minusHours(properties.booking().cancelBeforeHours());
    }

    public int maxRedeemablePoints(Booking booking) {
        if (booking.getUser() == null) {
            return 0;
        }
        BigDecimal afterPromotion = subtotal(booking).subtract(booking.getPromotionDiscount());
        return pointService.maxRedeemable(booking.getUser(), afterPromotion);
    }

    // ------------------------------------------------------------------ hold seats

    /**
     * Step 1 of online booking: holds the chosen seats for the member for a few minutes.
     *
     * @throws SeatUnavailableException when another customer got one of the seats first
     */
    @Transactional
    public Booking holdSeats(Long userId, Long showtimeId, List<Long> showtimeSeatIds) {
        User user = userService.getById(userId);
        Showtime showtime = showtimeService.getDetailed(showtimeId);
        LocalDateTime now = LocalDateTime.now(clock);
        if (showtime.isCancelled()) {
            throw new BusinessException("Suất chiếu này đã bị huỷ");
        }
        if (!showtime.getStartTime().isAfter(now)) {
            throw new BusinessException("Suất chiếu đã bắt đầu, không thể đặt vé trực tuyến");
        }
        checkAge(user, showtime.getMovie(), now.toLocalDate());

        Booking booking = newBooking(showtime, SalesChannel.ONLINE, now);
        booking.setUser(user);
        booking.setExpiresAt(now.plusMinutes(properties.booking().holdMinutes()));
        booking = bookingRepository.save(booking);
        lockAndHoldSeats(booking, showtimeSeatIds, now);
        recalculate(booking);
        log.info("Booking {} holds seats {} for user {}", booking.getCode(), booking.getSeatLabels(), user.getEmail());
        return booking;
    }

    static void checkAge(User user, Movie movie, LocalDate today) {
        int minAge = movie.getAgeRating().getMinAge();
        if (minAge == 0) {
            return;
        }
        int age = user.ageOn(today);
        if (age < 0) {
            throw new BusinessException("Phim \"" + movie.getTitle() + "\" giới hạn " + movie.getAgeRating().name()
                    + ". Vui lòng cập nhật ngày sinh trong trang Thành viên trước khi đặt vé.");
        }
        if (age < minAge) {
            throw new BusinessException("Phim \"" + movie.getTitle() + "\" chỉ dành cho khán giả từ " + minAge
                    + " tuổi trở lên. Bạn hiện " + age + " tuổi.");
        }
    }

    private Booking newBooking(Showtime showtime, SalesChannel channel, LocalDateTime now) {
        Booking booking = new Booking();
        booking.setCode(uniqueCode());
        booking.setShowtime(showtime);
        booking.setChannel(channel);
        booking.setStatus(BookingStatus.PENDING);
        booking.setCreatedAt(now);
        return booking;
    }

    private void lockAndHoldSeats(Booking booking, List<Long> showtimeSeatIds, LocalDateTime now) {
        List<Long> ids = showtimeSeatIds == null ? List.of() : showtimeSeatIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            throw new BusinessException("Vui lòng chọn ít nhất một ghế");
        }
        List<ShowtimeSeat> seats = showtimeSeatRepository.findForUpdate(booking.getShowtime().getId(), ids);
        if (seats.size() != ids.size()) {
            throw new BusinessException("Có ghế không thuộc suất chiếu này");
        }
        List<String> taken = seats.stream()
                .filter(ss -> ShowtimeService.effectiveStatus(ss, now) != SeatStatus.AVAILABLE)
                .map(ss -> ss.getSeat().getLabel())
                .sorted()
                .toList();
        if (!taken.isEmpty()) {
            throw new SeatUnavailableException(taken);
        }
        BigDecimal ticketAmount = BigDecimal.ZERO;
        for (ShowtimeSeat ss : seats) {
            ss.hold(booking, booking.getExpiresAt() == null ? now : booking.getExpiresAt());
            booking.getSeats().add(ss);
            ticketAmount = ticketAmount.add(ss.getPrice());
        }
        booking.setTicketAmount(ticketAmount);
    }

    private String uniqueCode() {
        String code;
        do {
            code = CodeGenerator.bookingCode();
        } while (bookingRepository.existsByCode(code));
        return code;
    }

    // ------------------------------------------------------------------ combos, promotion, points

    @Transactional
    public Booking updateCombos(String code, Long userId, Map<Long, Integer> quantities) {
        Booking booking = getEditable(code, userId);
        applyCombos(booking, quantities);
        recalculate(booking);
        return booking;
    }

    private void applyCombos(Booking booking, Map<Long, Integer> quantities) {
        booking.getCombos().clear();
        BigDecimal comboAmount = BigDecimal.ZERO;
        if (quantities != null) {
            for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
                int quantity = entry.getValue() == null ? 0 : entry.getValue();
                if (quantity <= 0) {
                    continue;
                }
                if (quantity > MAX_COMBO_QUANTITY) {
                    throw new BusinessException("Mỗi combo tối đa " + MAX_COMBO_QUANTITY + " phần");
                }
                Combo combo = comboService.getById(entry.getKey());
                if (!combo.isActive()) {
                    throw new BusinessException("Combo \"" + combo.getName() + "\" hiện không bán");
                }
                BookingCombo line = new BookingCombo();
                line.setBooking(booking);
                line.setCombo(combo);
                line.setQuantity(quantity);
                line.setUnitPrice(combo.getPrice());
                booking.getCombos().add(line);
                comboAmount = comboAmount.add(line.getLineTotal());
            }
        }
        booking.setComboAmount(comboAmount);
    }

    @Transactional
    public Booking applyPromotion(String code, Long userId, String promotionCode) {
        Booking booking = getEditable(code, userId);
        Promotion promotion = promotionService.findUsable(promotionCode, subtotal(booking));
        booking.setPromotion(promotion);
        recalculate(booking);
        return booking;
    }

    @Transactional
    public Booking removePromotion(String code, Long userId) {
        Booking booking = getEditable(code, userId);
        booking.setPromotion(null);
        recalculate(booking);
        return booking;
    }

    @Transactional
    public Booking usePoints(String code, Long userId, int points) {
        Booking booking = getEditable(code, userId);
        if (points < 0) {
            throw new BusinessException("Số điểm không hợp lệ");
        }
        int max = maxRedeemablePoints(booking);
        if (points > max) {
            throw new BusinessException("Bạn chỉ có thể dùng tối đa " + max + " điểm cho đơn này");
        }
        booking.setPointsUsed(points);
        recalculate(booking);
        return booking;
    }

    private Booking getEditable(String code, Long userId) {
        Booking booking = getByCode(code);
        checkOwner(booking, userId, Role.USER);
        ensurePayable(booking);
        return booking;
    }

    private void ensurePayable(Booking booking) {
        if (!booking.isPending()) {
            throw new BusinessException("Đơn " + booking.getCode() + " " + booking.getStatus().getLabel().toLowerCase()
                    + ", không thể thay đổi");
        }
        if (isHoldExpired(booking)) {
            throw new BusinessException("Đã hết thời gian giữ ghế. Vui lòng đặt lại.");
        }
    }

    /**
     * True when an unpaid booking is past its hold time. The cleanup job only runs every 30 seconds,
     * so the seats may still look held for a moment after this becomes true.
     */
    public boolean isHoldExpired(Booking booking) {
        return booking.isPending() && booking.getExpiresAt() != null
                && booking.getExpiresAt().isBefore(LocalDateTime.now(clock));
    }

    private static BigDecimal subtotal(Booking booking) {
        return booking.getTicketAmount().add(booking.getComboAmount());
    }

    /** Recomputes discounts and the total. Called after every change to the order. */
    void recalculate(Booking booking) {
        BigDecimal subtotal = subtotal(booking);
        BigDecimal promotionDiscount = BigDecimal.ZERO;
        if (booking.getPromotion() != null) {
            try {
                PromotionService.checkUsable(booking.getPromotion(), subtotal, LocalDate.now(clock));
                promotionDiscount = PromotionService.discountFor(booking.getPromotion(), subtotal);
            } catch (BusinessException e) {
                // The order changed and the code no longer applies (e.g. below the minimum order).
                booking.setPromotion(null);
            }
        }
        booking.setPromotionDiscount(promotionDiscount);
        BigDecimal afterPromotion = subtotal.subtract(promotionDiscount);
        if (booking.getUser() != null) {
            int max = pointService.maxRedeemable(booking.getUser(), afterPromotion);
            booking.setPointsUsed(Math.min(booking.getPointsUsed(), max));
        } else {
            booking.setPointsUsed(0);
        }
        BigDecimal total = afterPromotion.subtract(pointService.valueOf(booking.getPointsUsed()));
        booking.setTotalAmount(total.max(BigDecimal.ZERO));
    }

    // ------------------------------------------------------------------ payment

    /** Demo payment: the member clicks "Pay" and the order is paid at once. */
    @Transactional
    public Booking payWithMock(String code, Long userId) {
        Booking booking = getByCode(code);
        checkOwner(booking, userId, Role.USER);
        ensurePayable(booking);
        return confirmPayment(booking, PaymentMethod.MOCK, "MOCK-" + booking.getCode(), null);
    }

    /**
     * Marks a booking as paid: seats become BOOKED, the promotion use and the points are recorded,
     * and a ticket e-mail is sent after the transaction commits. Calling it twice is harmless.
     * A payment that arrives after the hold time is refused even if the cleanup job has not released
     * the seats yet.
     */
    @Transactional
    public Booking confirmPayment(Booking booking, PaymentMethod method, String transactionRef, String gatewayTransactionNo) {
        if (booking.isPaid()) {
            return booking;
        }
        if (!booking.isPending()) {
            throw new BusinessException("Đơn " + booking.getCode() + " " + booking.getStatus().getLabel().toLowerCase()
                    + ", không thể thanh toán");
        }
        if (isHoldExpired(booking)) {
            throw new BusinessException("Đơn " + booking.getCode() + " đã hết thời gian giữ ghế, không thể xác nhận thanh toán");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<Long> seatIds = booking.getSeats().stream().map(ShowtimeSeat::getId).toList();
        List<ShowtimeSeat> seats = showtimeSeatRepository.findForUpdate(booking.getShowtime().getId(), seatIds);
        boolean stillHeld = !seats.isEmpty() && seats.stream().allMatch(ss ->
                ss.getStatus() == SeatStatus.HELD && ss.getBooking() != null && ss.getBooking().getId().equals(booking.getId()));
        if (!stillHeld) {
            throw new BusinessException("Ghế của đơn " + booking.getCode() + " đã được giải phóng do quá thời gian giữ chỗ");
        }

        Promotion promotion = booking.getPromotion();
        if (promotion != null) {
            if (promotion.getUsageLimit() != null && promotion.getUsedCount() >= promotion.getUsageLimit()) {
                throw new BusinessException("Mã giảm giá " + promotion.getCode() + " vừa hết lượt. Vui lòng bỏ mã và thanh toán lại.");
            }
            promotion.setUsedCount(promotion.getUsedCount() + 1);
        }

        User user = booking.getUser();
        if (user != null && booking.getPointsUsed() > 0) {
            if (booking.getPointsUsed() > user.getPoints()) {
                throw new BusinessException("Số điểm hiện có không đủ");
            }
            pointService.record(user, booking, PointType.REDEEM, -booking.getPointsUsed(), "Dùng điểm cho đơn " + booking.getCode());
        }

        seats.forEach(ShowtimeSeat::markBooked);
        booking.setStatus(BookingStatus.PAID);
        booking.setPaidAt(now);

        if (user != null) {
            int earned = pointService.pointsEarnedFor(booking.getTotalAmount());
            booking.setPointsEarned(earned);
            pointService.record(user, booking, PointType.EARN, earned, "Tích điểm đơn " + booking.getCode());
        }

        Payment payment = paymentRepository.findByTransactionRef(transactionRef).orElseGet(Payment::new);
        payment.setBooking(booking);
        payment.setMethod(method);
        payment.setAmount(booking.getTotalAmount());
        payment.setTransactionRef(transactionRef);
        payment.setGatewayTransactionNo(gatewayTransactionNo);
        payment.setStatus(PaymentStatus.SUCCESS);
        if (payment.getCreatedAt() == null) {
            payment.setCreatedAt(now);
        }
        payment.setPaidAt(now);
        paymentRepository.save(payment);

        eventPublisher.publishEvent(new BookingPaidEvent(booking.getCode()));
        log.info("Booking {} paid by {} ({}đ)", booking.getCode(), method, booking.getTotalAmount());
        return booking;
    }

    // ------------------------------------------------------------------ cancel / expire

    @Transactional
    public Booking cancel(String code, Long viewerId, Role viewerRole) {
        Booking booking = getByCode(code);
        checkOwner(booking, viewerId, viewerRole);
        LocalDateTime now = LocalDateTime.now(clock);
        if (booking.isPending()) {
            releaseSeats(booking);
            booking.setStatus(BookingStatus.CANCELLED);
            booking.setCancelledAt(now);
            return booking;
        }
        if (!booking.isPaid()) {
            throw new BusinessException("Đơn " + booking.getCode() + " " + booking.getStatus().getLabel().toLowerCase());
        }
        if (booking.getCheckedInAt() != null) {
            throw new BusinessException("Vé đã được soát, không thể huỷ");
        }
        boolean staff = viewerRole == Role.STAFF || viewerRole == Role.ADMIN;
        if (!staff && now.isAfter(cancelDeadline(booking))) {
            throw new BusinessException("Chỉ được huỷ vé trước giờ chiếu " + properties.booking().cancelBeforeHours() + " tiếng");
        }

        releaseSeats(booking);
        User user = booking.getUser();
        if (user != null) {
            pointService.record(user, booking, PointType.REVERT, booking.getPointsUsed(), "Hoàn điểm đã dùng, huỷ đơn " + booking.getCode());
            pointService.record(user, booking, PointType.REVERT, -booking.getPointsEarned(), "Thu hồi điểm đã tích, huỷ đơn " + booking.getCode());
        }
        Promotion promotion = booking.getPromotion();
        if (promotion != null && promotion.getUsedCount() > 0) {
            promotion.setUsedCount(promotion.getUsedCount() - 1);
        }
        paymentRepository.findByBookingIdOrderByCreatedAtDesc(booking.getId()).stream()
                .filter(p -> p.getStatus() == PaymentStatus.SUCCESS)
                .forEach(p -> p.setStatus(PaymentStatus.REFUNDED));
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledAt(now);
        log.info("Booking {} cancelled", booking.getCode());
        return booking;
    }

    /** Releases the seats of every unpaid booking whose hold time is over. Run by a scheduled job. */
    @Transactional
    public int expireOverdueBookings() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Booking> overdue = bookingRepository.findByStatusAndExpiresAtBefore(BookingStatus.PENDING, now);
        for (Booking booking : overdue) {
            releaseSeats(booking);
            booking.setStatus(BookingStatus.EXPIRED);
        }
        return overdue.size();
    }

    private void releaseSeats(Booking booking) {
        for (ShowtimeSeat ss : booking.getSeats()) {
            if (ss.getBooking() != null && ss.getBooking().getId().equals(booking.getId())) {
                ss.release();
            }
        }
    }

    // ------------------------------------------------------------------ counter sales and check-in

    /** A staff member sells tickets at the counter; the customer pays cash at once. */
    @Transactional
    public Booking sellAtCounter(Long staffId, Long showtimeId, CounterSaleForm form) {
        User staff = userService.getById(staffId);
        Showtime showtime = showtimeService.getDetailed(showtimeId);
        LocalDateTime now = LocalDateTime.now(clock);
        if (showtime.isCancelled()) {
            throw new BusinessException("Suất chiếu này đã bị huỷ");
        }
        if (!showtime.getEndTime().isAfter(now)) {
            throw new BusinessException("Suất chiếu đã kết thúc");
        }
        Booking booking = newBooking(showtime, SalesChannel.COUNTER, now);
        booking.setSoldBy(staff);
        booking.setCustomerName(form.getCustomerName());
        booking.setCustomerPhone(form.getCustomerPhone());
        booking.setCustomerEmail(form.getCustomerEmail());
        booking.setExpiresAt(now.plusMinutes(properties.booking().holdMinutes()));
        booking = bookingRepository.save(booking);
        lockAndHoldSeats(booking, form.getShowtimeSeatIds(), now);
        applyCombos(booking, form.getComboQuantities());
        recalculate(booking);
        return confirmPayment(booking, PaymentMethod.CASH, "CASH-" + booking.getCode(), null);
    }

    /** Ticket check at the cinema entrance. */
    @Transactional
    public Booking checkIn(String code) {
        Booking booking = getByCode(code.trim().toUpperCase(Locale.ROOT));
        if (!booking.isPaid()) {
            throw new BusinessException("Vé " + booking.getCode() + " " + booking.getStatus().getLabel().toLowerCase()
                    + ", không thể soát vé");
        }
        if (booking.getCheckedInAt() != null) {
            throw new BusinessException("Vé " + booking.getCode() + " đã được soát lúc " + booking.getCheckedInAt().toLocalTime().withNano(0));
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Showtime showtime = booking.getShowtime();
        LocalDateTime opens = showtime.getStartTime().minusMinutes(properties.booking().checkInOpensMinutesBefore());
        if (now.isBefore(opens)) {
            throw new BusinessException("Chưa đến giờ soát vé. Cổng mở từ " + opens.toLocalTime() + " ngày " + opens.toLocalDate());
        }
        if (now.isAfter(showtime.getEndTime())) {
            throw new BusinessException("Suất chiếu đã kết thúc");
        }
        booking.setCheckedInAt(now);
        return booking;
    }

    // ------------------------------------------------------------------ helpers

    private static void checkOwner(Booking booking, Long viewerId, Role viewerRole) {
        if (viewerRole == Role.STAFF || viewerRole == Role.ADMIN) {
            return;
        }
        if (booking.getUser() == null || !booking.getUser().getId().equals(viewerId)) {
            throw new AccessDeniedBookingException();
        }
    }
}
