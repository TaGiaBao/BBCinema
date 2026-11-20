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
import com.bbcinema.repository.CounterShiftRepository;
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
    private final CounterShiftRepository counterShiftRepository;
    private final InventoryService inventoryService;
    private final ShowtimeService showtimeService;
    private final UserService userService;
    private final ComboService comboService;
    private final PromotionService promotionService;
    private final PointService pointService;
    private final MembershipService membershipService;
    private final MemberCodeService memberCodeService;

    /** Refusal for a member trying to change a ticket bought at the counter. */
    public static final String COUNTER_ONLY = "Vé mua tại quầy chỉ được thay đổi hoặc huỷ tại quầy.";

    /** Refusal for a counter sale with a member attached whose name staff did not confirm. */
    public static final String MEMBER_NOT_CONFIRMED = "Vui lòng xác nhận họ tên khách thành viên trước khi bán vé.";
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

    /**
     * Loads a booking the viewer wants to change (combos, promotion, points, payment, cancel). Besides the owner
     * check, a member cannot change a ticket bought at the counter: it was sold, paid and (if needed) refunded
     * through a till shift, so the member may only view it online. Every owner action goes through here.
     */
    public Booking getForOwnerChange(String code, Long viewerId, Role viewerRole) {
        Booking booking = getByCode(code);
        checkOwner(booking, viewerId, viewerRole);
        if (counterOnly(booking, viewerRole)) {
            throw new BusinessException(COUNTER_ONLY);
        }
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

    /**
     * Whether the viewer may cancel the booking from its ticket page. Members never may for a ticket bought at
     * the counter: that goes back to the counter (see {@link #getForOwnerChange}).
     */
    public boolean canCancel(Booking booking, Role viewerRole) {
        if (counterOnly(booking, viewerRole)) {
            return false;
        }
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
        if (!booking.getCombos().isEmpty()) {
            // The snacks come from the stock of the cinema where the film is shown.
            inventoryService.checkAvailable(booking.getShowtime().getRoom().getCinema(), booking.getCombos());
        }
        booking.setComboAmount(comboAmount);
    }

    @Transactional
    public Booking applyPromotion(String code, Long userId, String promotionCode) {
        Booking booking = getEditable(code, userId);
        Promotion promotion = promotionService.findUsable(promotionCode, subtotal(booking), booking.getUser());
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
        Booking booking = getForOwnerChange(code, userId, Role.USER);
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
        // 1. Membership tier discount (members only), 2. promotion code, 3. points.
        BigDecimal fullPrice = subtotal(booking);
        MemberTier tier = booking.getUser() == null ? null : membershipService.tierOf(booking.getUser());
        BigDecimal tierDiscount = tier == null ? BigDecimal.ZERO : membershipService.discountOn(tier, fullPrice);
        booking.setMemberTier(tier);
        booking.setTierDiscount(tierDiscount);
        BigDecimal subtotal = fullPrice.subtract(tierDiscount);
        BigDecimal promotionDiscount = BigDecimal.ZERO;
        if (booking.getPromotion() != null) {
            try {
                PromotionService.checkUsable(booking.getPromotion(), subtotal, LocalDate.now(clock), booking.getUser());
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
        Booking booking = getForOwnerChange(code, userId, Role.USER);
        ensurePayable(booking);
        return confirmPayment(booking, PaymentMethod.MOCK, "MOCK-" + booking.getCode(), null);
    }

    /**
     * Starts a bank transfer: the booking waits for the money, with a pending payment the QR page refers to.
     * Members may only start it for their own booking; staff for any (counter sales).
     */
    @Transactional
    public Booking startBankTransfer(String code, Long viewerId, Role viewerRole) {
        Booking booking = getForOwnerChange(code, viewerId, viewerRole);
        ensurePayable(booking);
        String ref = bankTransferRef(booking.getCode());
        Payment payment = paymentRepository.findByTransactionRef(ref).orElseGet(Payment::new);
        payment.setBooking(booking);
        payment.setMethod(PaymentMethod.BANK_QR);
        payment.setAmount(booking.getTotalAmount());
        payment.setTransactionRef(ref);
        if (payment.getCreatedAt() == null) {
            payment.setCreatedAt(LocalDateTime.now(clock));
        }
        paymentRepository.save(payment);
        return booking;
    }

    /**
     * The bank received a transfer for this booking (reported by SePay, by a member of staff, or by the demo
     * button). Less money than the total is refused; a second report of the same booking changes nothing.
     */
    @Transactional
    public Booking confirmBankTransfer(String code, BigDecimal amountReceived, String bankReference) {
        Booking booking = getByCode(code);
        if (booking.isPaid()) {
            return booking;
        }
        if (amountReceived.compareTo(booking.getTotalAmount()) < 0) {
            throw new BusinessException("Đơn " + code + " cần " + booking.getTotalAmount().toPlainString()
                    + "đ nhưng chỉ nhận được " + amountReceived.toPlainString() + "đ");
        }
        return confirmPayment(booking, PaymentMethod.BANK_QR, bankTransferRef(code), bankReference);
    }

    public static String bankTransferRef(String bookingCode) {
        return "QR-" + bookingCode;
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
            PromotionService.checkOwner(promotion, booking.getUser());
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
        // Paid: the combos' ingredients leave the stock (the money is in, so this never refuses).
        inventoryService.takeForOrder(booking);

        eventPublisher.publishEvent(new BookingPaidEvent(booking.getCode()));
        log.info("Booking {} paid by {} ({}đ)", booking.getCode(), method, booking.getTotalAmount());
        return booking;
    }

    // ------------------------------------------------------------------ cancel / expire

    @Transactional
    public Booking cancel(String code, Long viewerId, Role viewerRole) {
        Booking booking = getForOwnerChange(code, viewerId, viewerRole);
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
        if (viewerRole == Role.STAFF || viewerRole == Role.MANAGER) {
            // At the counter money only comes in; a refund goes through refundAtCounter in a shift, approved by the manager.
            throw new BusinessException("Nhân viên quầy không được hoàn tiền. Cần quản lý quầy duyệt huỷ vé tại quầy.");
        }
        boolean admin = viewerRole == Role.ADMIN;
        if (!admin && now.isAfter(cancelDeadline(booking))) {
            throw new BusinessException("Chỉ được huỷ vé trước giờ chiếu " + properties.booking().cancelBeforeHours() + " tiếng");
        }
        return refund(booking, now, admin ? userService.getById(viewerId) : null, null);
    }

    /**
     * A manager approved cancelling a paid ticket at the counter: the money is handed back from the drawer of
     * {@code shift}. Checked-in tickets cannot be refunded.
     */
    @Transactional
    public Booking refundAtCounter(String code, User manager, CounterShift shift) {
        Booking booking = getByCode(code);
        if (!booking.isPaid()) {
            throw new BusinessException("Đơn " + booking.getCode() + " " + booking.getStatus().getLabel().toLowerCase()
                    + ", không có tiền để hoàn");
        }
        return refund(booking, LocalDateTime.now(clock), manager, shift);
    }

    private Booking refund(Booking booking, LocalDateTime now, User refundedBy, CounterShift shift) {
        if (booking.getCheckedInAt() != null) {
            throw new BusinessException("Vé đã được soát, không thể huỷ");
        }
        releaseSeats(booking);
        inventoryService.returnForOrder(booking);
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
                .forEach(p -> {
                    p.setStatus(PaymentStatus.REFUNDED);
                    p.setRefundedAt(now);
                    p.setRefundedBy(refundedBy);
                    p.setRefundShift(shift);
                });
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

    /**
     * A staff member sells tickets at the counter. Cash and card (POS terminal, with its approval code) are paid
     * at once. A bank transfer leaves the booking pending until the money arrives; e-wallets need their merchant
     * account and are refused until one is set up.
     * <p>
     * When staff attached a member (by member code or phone), the booking belongs to that member: the tier
     * discount comes from the same {@link #recalculate} as online orders and the member earns points when it is
     * paid. Promotion codes and point redemption stay online-only.
     */
    @Transactional
    public Booking sellAtCounter(Long staffId, Long showtimeId, CounterSaleForm form) {
        PaymentMethod method = form.getPaymentMethod() == null ? PaymentMethod.CASH : form.getPaymentMethod();
        String approval = form.getCardApprovalCode() == null ? "" : form.getCardApprovalCode().trim().toUpperCase(Locale.ROOT);
        switch (method) {
            case CASH, BANK_QR -> { }
            case CARD -> {
                if (!approval.matches("[A-Z0-9]{4,12}")) {
                    throw new BusinessException("Nhập mã chuẩn chi in trên hoá đơn máy POS (4-12 chữ hoặc số)");
                }
            }
            default -> throw new BusinessException(method.getLabel() + " chưa được kết nối ở quầy");
        }
        User staff = userService.getById(staffId);
        CounterShift shift = counterShiftRepository.findFirstByStaffIdAndClosedAtIsNullOrderByOpenedAtDesc(staffId).orElse(null);
        if (staff.getRole() != Role.STAFF) {
            throw new BusinessException("Chỉ nhân viên quầy đang trong ca mới bán vé tại quầy");
        }
        if (shift == null) {
            throw new BusinessException("Bạn chưa vào ca. Nhờ quản lý mở ca trước khi bán vé.");
        }
        Showtime showtime = showtimeService.getDetailed(showtimeId);
        LocalDateTime now = LocalDateTime.now(clock);
        if (showtime.isCancelled()) {
            throw new BusinessException("Suất chiếu này đã bị huỷ");
        }
        if (!showtime.getEndTime().isAfter(now)) {
            throw new BusinessException("Suất chiếu đã kết thúc");
        }
        // The member code comes from the browser: resolve it again (enabled, confirmed member, never staff), and
        // only once staff confirmed who the customer is.
        User member = null;
        if (form.getMemberCode() != null && !form.getMemberCode().isBlank()) {
            if (!form.isMemberConfirmed()) {
                throw new BusinessException(MEMBER_NOT_CONFIRMED);
            }
            member = memberCodeService.memberForSale(form.getMemberCode());
        }
        Booking booking = newBooking(showtime, SalesChannel.COUNTER, now);
        if (member != null) {
            booking.setUser(member);
        }
        booking.setSoldBy(staff);
        booking.setShift(shift);
        booking.setCustomerName(form.getCustomerName());
        booking.setCustomerPhone(form.getCustomerPhone());
        booking.setCustomerEmail(form.getCustomerEmail());
        booking.setExpiresAt(now.plusMinutes(properties.booking().holdMinutes()));
        booking = bookingRepository.save(booking);
        lockAndHoldSeats(booking, form.getShowtimeSeatIds(), now);
        applyCombos(booking, form.getComboQuantities());
        recalculate(booking);
        if (method == PaymentMethod.BANK_QR) {
            return startBankTransfer(booking.getCode(), staffId, Role.STAFF);
        }
        BigDecimal received = form.getAmountReceived();
        BigDecimal change = counterChange(method, received, booking.getTotalAmount());
        String ref = method.name() + "-" + booking.getCode();
        confirmPayment(booking, method, ref, method == PaymentMethod.CARD ? approval : null);
        paymentRepository.findByTransactionRef(ref).ifPresent(p -> {
            p.setAmountReceived(received);
            p.setChangeAmount(change);
        });
        return booking;
    }

    /** The successful payment of a paid booking, if any (shown on the counter receipt). */
    public Payment paymentOf(Booking booking) {
        return paymentRepository.findByBookingIdOrderByCreatedAtDesc(booking.getId()).stream()
                .filter(p -> p.getStatus() == PaymentStatus.SUCCESS).findFirst().orElse(null);
    }

    /**
     * Checks the money taken at the counter against the order total and returns the change to hand back.
     * Cash must cover the total; a card slip must show exactly the total, otherwise the POS sale is wrong.
     */
    static BigDecimal counterChange(PaymentMethod method, BigDecimal received, BigDecimal total) {
        if (method == PaymentMethod.CASH) {
            if (received == null) {
                throw new BusinessException("Nhập số tiền khách đưa");
            }
            if (received.compareTo(total) < 0) {
                throw new BusinessException("Khách đưa thiếu " + money(total.subtract(received))
                        + " (tổng tiền " + money(total) + ", khách đưa " + money(received) + ")");
            }
            return received.subtract(total);
        }
        if (received == null) {
            throw new BusinessException("Nhập số tiền in trên hoá đơn máy POS");
        }
        int diff = received.compareTo(total);
        if (diff != 0) {
            throw new BusinessException("Hoá đơn POS " + (diff > 0 ? "thừa " : "thiếu ") + money(received.subtract(total).abs())
                    + " so với tổng tiền " + money(total) + ". Huỷ giao dịch trên máy POS và quẹt lại đúng số tiền.");
        }
        return BigDecimal.ZERO;
    }

    private static String money(BigDecimal amount) {
        return String.format(Locale.ROOT, "%,d", amount.longValue()).replace(',', '.') + "đ";
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

    /** A member looking at a ticket sold at the counter: view only. */
    private static boolean counterOnly(Booking booking, Role viewerRole) {
        return booking.getChannel() == SalesChannel.COUNTER && !viewerRole.isInternal();
    }

    private static void checkOwner(Booking booking, Long viewerId, Role viewerRole) {
        if (viewerRole.isInternal()) {
            return;
        }
        if (booking.getUser() == null || !booking.getUser().getId().equals(viewerId)) {
            throw new AccessDeniedBookingException();
        }
    }
}
