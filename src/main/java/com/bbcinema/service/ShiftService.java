package com.bbcinema.service;

import com.bbcinema.entity.Booking;
import com.bbcinema.entity.Cinema;
import com.bbcinema.entity.CounterShift;
import com.bbcinema.entity.Payment;
import com.bbcinema.entity.User;
import com.bbcinema.entity.enums.BookingStatus;
import com.bbcinema.entity.enums.PaymentMethod;
import com.bbcinema.entity.enums.Role;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.exception.NotFoundException;
import com.bbcinema.repository.BookingRepository;
import com.bbcinema.repository.CinemaRepository;
import com.bbcinema.repository.CounterShiftRepository;
import com.bbcinema.repository.PaymentRepository;
import com.bbcinema.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Till shifts at the counter. Staff only take money in: the counter manager (a MANAGER account of that cinema),
 * standing at the counter, types their e-mail and password to open a shift with the opening float, to approve a
 * refund, and to close the shift after counting the drawer. The general manager (ADMIN) works online and never
 * opens or closes a shift. Closing freezes the shift's totals so one shift's money is never mixed with the next.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftService {

    public static final String WRONG_MANAGER = "Email hoặc mật khẩu quản lý quầy không đúng";

    private final CounterShiftRepository shiftRepository;
    private final PaymentRepository paymentRepository;
    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final CinemaRepository cinemaRepository;
    private final BookingService bookingService;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    /** Money of a shift, worked out from its sales and refunds. */
    public record Summary(CounterShift shift, int cashSales, BigDecimal cashReceived, BigDecimal changeGiven,
                          int cashRefunds, BigDecimal cashRefunded, int cardSales, BigDecimal cardTotal,
                          int transferSales, BigDecimal transferTotal) {

        /** Cash taken during the shift (handed over by customers, minus change and refunds): what is paid in. */
        public BigDecimal netCash() {
            return cashReceived.subtract(changeGiven).subtract(cashRefunded);
        }

        /** Cash that should be in the drawer: the opening float, which stays, plus the cash taken. */
        public BigDecimal expectedCash() {
            return shift.getOpeningFloat().add(netCash());
        }
    }

    public Optional<CounterShift> findOpen(Long staffId) {
        return shiftRepository.findFirstByStaffIdAndClosedAtIsNullOrderByOpenedAtDesc(staffId);
    }

    public CounterShift requireOpen(Long staffId) {
        return findOpen(staffId).orElseThrow(() -> new BusinessException("Bạn chưa vào ca. Nhờ quản lý mở ca."));
    }

    public CounterShift getDetailed(Long id) {
        return shiftRepository.findDetailedById(id).orElseThrow(() -> new NotFoundException("Không tìm thấy ca bán vé"));
    }

    public List<CounterShift> recent() {
        return shiftRepository.findTop100ByOrderByOpenedAtDesc();
    }

    /** Shifts a viewer may see: the general manager sees every cinema, a counter manager only their own. */
    public List<CounterShift> recentFor(Long viewerId) {
        User viewer = userRepository.findById(viewerId).orElseThrow(() -> new NotFoundException("Không tìm thấy tài khoản"));
        if (viewer.getRole() == Role.ADMIN) {
            return recent();
        }
        Long cinemaId = viewer.getCinema() == null ? null : viewer.getCinema().getId();
        return recent().stream().filter(s -> sameCinema(s, cinemaId)).toList();
    }

    /** Open shifts at a counter manager's cinema, to close one a staff member left behind. */
    public List<CounterShift> openAtCinemaOf(Long managerId) {
        User manager = requireManagerAccount(managerId);
        Long cinemaId = manager.getCinema() == null ? null : manager.getCinema().getId();
        return recent().stream().filter(CounterShift::isOpen).filter(s -> sameCinema(s, cinemaId)).toList();
    }

    public boolean canView(CounterShift shift, Long viewerId) {
        User viewer = userRepository.findById(viewerId).orElse(null);
        return viewer != null && (viewer.getRole() == Role.ADMIN
                || viewer.getRole() == Role.MANAGER && sameCinema(shift, viewer.getCinema() == null ? null : viewer.getCinema().getId()));
    }

    private static boolean sameCinema(CounterShift shift, Long cinemaId) {
        return cinemaId == null || shift.getCinema() == null || shift.getCinema().getId().equals(cinemaId);
    }

    /** The float of the last shift, offered as the default when the next one opens. */
    public BigDecimal suggestedFloat() {
        return shiftRepository.findFirstByOrderByOpenedAtDesc().map(CounterShift::getOpeningFloat).orElse(BigDecimal.valueOf(1_000_000));
    }

    @Transactional
    public CounterShift open(Long staffId, Long cinemaId, String managerEmail, String managerPassword, BigDecimal openingFloat) {
        User manager = verifyManager(managerEmail, managerPassword);
        checkCinema(manager, cinemaId);
        if (openingFloat == null || openingFloat.signum() < 0) {
            throw new BusinessException("Nhập quỹ đầu ca (từ 0đ trở lên)");
        }
        if (findOpen(staffId).isPresent()) {
            throw new BusinessException("Nhân viên này đang có ca chưa kết");
        }
        User staff = userRepository.findById(staffId).orElseThrow(() -> new NotFoundException("Không tìm thấy nhân viên"));
        if (staff.getRole() != Role.STAFF) {
            throw new BusinessException("Chỉ mở ca cho tài khoản nhân viên quầy");
        }
        Cinema cinema = cinemaId == null ? null : cinemaRepository.findById(cinemaId).orElse(null);
        CounterShift shift = new CounterShift();
        shift.setStaff(staff);
        shift.setCinema(cinema);
        shift.setOpenedAt(LocalDateTime.now(clock));
        shift.setOpenedBy(manager);
        shift.setOpeningFloat(openingFloat);
        shift = shiftRepository.save(shift);
        log.info("Shift {} opened for {} by {} with float {}", shift.getId(), staff.getEmail(), manager.getEmail(), openingFloat);
        return shift;
    }

    public Summary summary(CounterShift shift) {
        int cash = 0, card = 0, transfer = 0;
        BigDecimal received = BigDecimal.ZERO, change = BigDecimal.ZERO, cardTotal = BigDecimal.ZERO, transferTotal = BigDecimal.ZERO;
        for (Payment p : paymentRepository.findShiftSales(shift.getId())) {
            switch (p.getMethod()) {
                case CASH -> {
                    cash++;
                    received = received.add(p.getAmountReceived() == null ? p.getAmount() : p.getAmountReceived());
                    change = change.add(p.getChangeAmount() == null ? BigDecimal.ZERO : p.getChangeAmount());
                }
                case CARD -> {
                    card++;
                    cardTotal = cardTotal.add(p.getAmount());
                }
                case BANK_QR -> {
                    transfer++;
                    transferTotal = transferTotal.add(p.getAmount());
                }
                default -> { }
            }
        }
        List<Payment> cashRefunds = paymentRepository.findShiftRefunds(shift.getId()).stream()
                .filter(p -> p.getMethod() == PaymentMethod.CASH).toList();
        BigDecimal refunded = cashRefunds.stream().map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Summary(shift, cash, received, change, cashRefunds.size(), refunded, card, cardTotal, transfer, transferTotal);
    }

    /** The manager counted the drawer: freeze the totals and close the shift. */
    @Transactional
    public CounterShift close(Long staffId, String managerEmail, String managerPassword, BigDecimal countedCash, String note) {
        User manager = verifyManager(managerEmail, managerPassword);
        CounterShift shift = requireOpen(staffId);
        checkCinema(manager, shift.getCinema() == null ? null : shift.getCinema().getId());
        return close(shift, manager, countedCash, note);
    }

    /**
     * The counter manager, signed in on the counter computer, closes a shift a staff member left open.
     * Only a MANAGER of the shift's cinema can do this; the general manager cannot close shifts.
     */
    @Transactional
    public CounterShift closeAtCounter(Long shiftId, Long managerId, BigDecimal countedCash, String note) {
        User manager = requireManagerAccount(managerId);
        CounterShift shift = getDetailed(shiftId);
        if (!shift.isOpen()) {
            throw new BusinessException("Ca #" + shiftId + " đã kết lúc " + shift.getClosedAt().toLocalTime().withNano(0));
        }
        checkCinema(manager, shift.getCinema() == null ? null : shift.getCinema().getId());
        return close(shift, manager, countedCash, note);
    }

    private CounterShift close(CounterShift shift, User manager, BigDecimal countedCash, String note) {
        if (countedCash == null || countedCash.signum() < 0) {
            throw new BusinessException("Nhập số tiền mặt đếm được trong két");
        }
        if (bookingRepository.countByShiftIdAndStatus(shift.getId(), BookingStatus.PENDING) > 0) {
            throw new BusinessException("Còn đơn chuyển khoản đang chờ tiền. Xác nhận hoặc huỷ đơn đó trước khi kết ca.");
        }
        Summary s = summary(shift);
        shift.setCashSales(s.cashSales());
        shift.setCashReceived(s.cashReceived());
        shift.setChangeGiven(s.changeGiven());
        shift.setCashRefunds(s.cashRefunds());
        shift.setCashRefunded(s.cashRefunded());
        shift.setCardSales(s.cardSales());
        shift.setCardTotal(s.cardTotal());
        shift.setTransferSales(s.transferSales());
        shift.setTransferTotal(s.transferTotal());
        shift.setExpectedCash(s.expectedCash());
        shift.setCountedCash(countedCash);
        shift.setNote(note == null || note.isBlank() ? null : note.trim());
        shift.setClosedAt(LocalDateTime.now(clock));
        shift.setClosedBy(manager);
        log.info("Shift {} closed by {}: expected {}, counted {}", shift.getId(), manager.getEmail(), s.expectedCash(), countedCash);
        return shift;
    }

    /** A manager approved cancelling a paid ticket; the cash comes out of the staff member's open shift. */
    @Transactional
    public Booking refund(Long staffId, String code, String managerEmail, String managerPassword) {
        User manager = verifyManager(managerEmail, managerPassword);
        CounterShift shift = requireOpen(staffId);
        checkCinema(manager, shift.getCinema() == null ? null : shift.getCinema().getId());
        return bookingService.refundAtCounter(code.trim().toUpperCase(java.util.Locale.ROOT), manager, shift);
    }

    /** The account must be an active counter manager (MANAGER) and the password must match. */
    public User verifyManager(String email, String password) {
        if (email == null || password == null || email.isBlank() || password.isEmpty()) {
            throw new BusinessException("Cần quản lý nhập email và mật khẩu");
        }
        User manager = userRepository.findByEmailIgnoreCase(email.trim())
                .filter(u -> u.getRole() == Role.MANAGER && u.isEnabled())
                .orElseThrow(() -> new BusinessException(WRONG_MANAGER));
        if (!passwordEncoder.matches(password, manager.getPasswordHash())) {
            throw new BusinessException(WRONG_MANAGER);
        }
        return manager;
    }

    private User requireManagerAccount(Long managerId) {
        return userRepository.findById(managerId)
                .filter(u -> u.getRole() == Role.MANAGER && u.isEnabled())
                .orElseThrow(() -> new BusinessException("Chỉ quản lý quầy mới kết ca được"));
    }

    /** A counter manager only works the tills of their own cinema. */
    private static void checkCinema(User manager, Long cinemaId) {
        if (manager.getCinema() != null && cinemaId != null && !manager.getCinema().getId().equals(cinemaId)) {
            throw new BusinessException("Quản lý quầy của " + manager.getCinema().getName() + " không duyệt được ca của rạp khác");
        }
    }
}
