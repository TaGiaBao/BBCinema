package com.bbcinema.service;

import com.bbcinema.entity.Showtime;
import com.bbcinema.entity.ShowtimeSeat;
import com.bbcinema.entity.TicketPrice;
import com.bbcinema.entity.enums.DayType;
import com.bbcinema.entity.enums.ScreenFormat;
import com.bbcinema.entity.enums.SeatStatus;
import com.bbcinema.entity.enums.SeatType;
import com.bbcinema.entity.enums.TimeSlot;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.repository.HolidayRepository;
import com.bbcinema.repository.ShowtimeRepository;
import com.bbcinema.repository.ShowtimeSeatRepository;
import com.bbcinema.repository.TicketPriceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * Looks up ticket prices. A price depends on four things: seat type, 2D/3D, the kind of day
 * (weekday, weekend or public holiday) and whether the show starts before or from 17:00.
 * Standard and VIP prices are set by the admin; a couple seat always costs two VIP seats of the
 * same format, day and time slot. A holiday price that has not been set falls back to the weekend price.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PricingService {

    static final LocalTime EVENING_START = LocalTime.of(17, 0);
    /** A couple seat is priced as this many VIP seats. */
    public static final int COUPLE_VIP_SEATS = 2;

    private final TicketPriceRepository ticketPriceRepository;
    private final HolidayRepository holidayRepository;
    private final ShowtimeRepository showtimeRepository;
    private final ShowtimeSeatRepository showtimeSeatRepository;
    private final Clock clock;
    private final PlatformTransactionManager transactionManager;

    /** A cell of the holiday price column: the holiday price of one seat type, format and time slot. */
    public record HolidayCell(SeatType seatType, ScreenFormat format, TimeSlot timeSlot) {
    }

    /** One line of the admin price grid: the weekday, weekend and holiday price of one seat type, format and time slot. */
    public record PriceGridRow(ScreenFormat format, TimeSlot timeSlot, SeatType seatType,
                               TicketPrice weekday, TicketPrice weekend, TicketPrice holiday) {
    }

    /** Weekday or weekend by the calendar only. Holidays need the database: see {@link #dayTypeFor}. */
    public static DayType dayTypeOf(LocalDateTime start) {
        DayOfWeek day = start.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY ? DayType.WEEKEND : DayType.WEEKDAY;
    }

    public static TimeSlot timeSlotOf(LocalDateTime start) {
        return start.toLocalTime().isBefore(EVENING_START) ? TimeSlot.BEFORE_17 : TimeSlot.FROM_17;
    }

    /**
     * The kind of day of a show, decided by the date it starts on (like the weekend rule): a show
     * starting at 23:30 on the eve of a holiday is not a holiday show.
     */
    @Transactional(readOnly = true)
    public DayType dayTypeFor(LocalDateTime start) {
        return holidayRepository.existsByDate(start.toLocalDate()) ? DayType.HOLIDAY : dayTypeOf(start);
    }

    @Transactional(readOnly = true)
    public BigDecimal priceFor(SeatType seatType, ScreenFormat format, LocalDateTime start) {
        if (seatType == SeatType.COUPLE) {
            return coupleFrom(priceFor(SeatType.VIP, format, start));
        }
        DayType dayType = dayTypeFor(start);
        TimeSlot slot = timeSlotOf(start);
        Optional<TicketPrice> row = ticketPriceRepository.findBySeatTypeAndFormatAndDayTypeAndTimeSlot(seatType, format, dayType, slot);
        if (row.isEmpty() && dayType == DayType.HOLIDAY) {
            row = ticketPriceRepository.findBySeatTypeAndFormatAndDayTypeAndTimeSlot(seatType, format, DayType.WEEKEND, slot);
        }
        return row.map(TicketPrice::getPrice)
                .orElseThrow(() -> new BusinessException(
                        "Chưa cấu hình giá vé cho " + seatType.getLabel() + " " + format.getLabel()
                                + ", " + dayType.getLabel() + ", " + slot.getLabel()));
    }

    public static BigDecimal coupleFrom(BigDecimal vipPrice) {
        return vipPrice.multiply(BigDecimal.valueOf(COUPLE_VIP_SEATS));
    }

    /** Seat types whose price the admin types in. The couple price is derived from VIP. */
    public static boolean isEditable(SeatType seatType) {
        return seatType != SeatType.COUPLE;
    }

    /**
     * The full price table for display: the stored standard and VIP rows, each VIP row followed
     * by the couple price worked out from it. The couple rows are not saved (their id is null).
     * Holiday prices that have not been set are added as unsaved rows carrying the weekend price
     * (marked {@link TicketPrice#isWeekendFallback()}), since that is what a holiday show costs then.
     */
    @Transactional(readOnly = true)
    public List<TicketPrice> findAll() {
        List<TicketPrice> stored = ticketPriceRepository.findAllByOrderByFormatAscDayTypeAscTimeSlotAscSeatTypeAsc().stream()
                .filter(row -> isEditable(row.getSeatType())) // rows left from when couple prices were typed in by hand
                .toList();
        Set<HolidayCell> holidaySet = new HashSet<>();
        for (TicketPrice row : stored) {
            if (row.getDayType() == DayType.HOLIDAY) {
                holidaySet.add(new HolidayCell(row.getSeatType(), row.getFormat(), row.getTimeSlot()));
            }
        }
        List<TicketPrice> table = new ArrayList<>();
        List<TicketPrice> fallbacks = new ArrayList<>();
        for (TicketPrice row : stored) {
            addWithCouple(table, row);
            if (row.getDayType() == DayType.WEEKEND
                    && !holidaySet.contains(new HolidayCell(row.getSeatType(), row.getFormat(), row.getTimeSlot()))) {
                TicketPrice fallback = new TicketPrice(row.getSeatType(), row.getFormat(), DayType.HOLIDAY, row.getTimeSlot(), row.getPrice());
                fallback.setWeekendFallback(true);
                fallbacks.add(fallback);
            }
        }
        fallbacks.forEach(row -> addWithCouple(table, row));
        return table;
    }

    private static void addWithCouple(List<TicketPrice> table, TicketPrice row) {
        table.add(row);
        if (row.getSeatType() == SeatType.VIP) {
            TicketPrice couple = new TicketPrice(SeatType.COUPLE, row.getFormat(), row.getDayType(), row.getTimeSlot(),
                    coupleFrom(row.getPrice()));
            couple.setWeekendFallback(row.isWeekendFallback());
            table.add(couple);
        }
    }

    /** The price table as the admin edits it: one line per format, time slot and seat type; one column per kind of day. */
    @Transactional(readOnly = true)
    public List<PriceGridRow> grid() {
        Map<String, TicketPrice> byKey = new HashMap<>();
        for (TicketPrice p : findAll()) {
            byKey.put(p.getFormat() + "_" + p.getTimeSlot() + "_" + p.getSeatType() + "_" + p.getDayType(), p);
        }
        List<PriceGridRow> rows = new ArrayList<>();
        for (ScreenFormat format : ScreenFormat.values()) {
            for (TimeSlot slot : TimeSlot.values()) {
                for (SeatType seatType : SeatType.values()) {
                    String key = format + "_" + slot + "_" + seatType + "_";
                    rows.add(new PriceGridRow(format, slot, seatType, byKey.get(key + DayType.WEEKDAY),
                            byKey.get(key + DayType.WEEKEND), byKey.get(key + DayType.HOLIDAY)));
                }
            }
        }
        return rows;
    }

    /** Saves the prices typed in the admin price table: price id -> new price. */
    public int updatePrices(Map<Long, BigDecimal> prices) {
        return updatePrices(prices, Map.of());
    }

    /**
     * Saves the admin price table: existing rows by id, plus holiday prices typed in for the first
     * time. An empty (null) value on a stored holiday row deletes it, so that cell goes back to the
     * weekend price; an empty weekday or weekend price is refused.
     * <p>
     * Weekday and weekend showtimes already created keep their prices. Holiday showtimes are
     * different: when a holiday price, or a weekend price a holiday falls back to, really changed,
     * the free seats of the upcoming holiday showtimes are re-priced after the new prices are
     * committed (see {@link #repriceAvailableSeatsOn}).
     *
     * @return the number of seats whose price changed
     */
    public int updatePrices(Map<Long, BigDecimal> prices, Map<HolidayCell, BigDecimal> newHolidayPrices) {
        Boolean holidayPricesChanged = transactionTemplate(TransactionDefinition.PROPAGATION_REQUIRED)
                .execute(status -> savePrices(prices, newHolidayPrices));
        if (!Boolean.TRUE.equals(holidayPricesChanged)) {
            return 0;
        }
        return repriceAvailableSeatsOn(holidayRepository.findDatesFrom(LocalDate.now(clock)));
    }

    /** @return true when a price a holiday show uses (a holiday or a weekend price) changed */
    private boolean savePrices(Map<Long, BigDecimal> prices, Map<HolidayCell, BigDecimal> newHolidayPrices) {
        boolean holidayPricesChanged = false;
        for (Map.Entry<Long, BigDecimal> entry : prices.entrySet()) {
            Long id = entry.getKey();
            BigDecimal price = entry.getValue();
            if (price != null) {
                checkPrice(price);
            }
            TicketPrice ticketPrice = ticketPriceRepository.findById(id)
                    .orElseThrow(() -> new BusinessException("Không tìm thấy dòng giá #" + id));
            if (!isEditable(ticketPrice.getSeatType())) {
                throw coupleNotTypedIn();
            }
            if (price == null && ticketPrice.getDayType() == DayType.HOLIDAY) {
                ticketPriceRepository.delete(ticketPrice); // back to the weekend price
                holidayPricesChanged = true;
                continue;
            }
            if (price == null) {
                throw new BusinessException("Giá vé phải là số");
            }
            if (ticketPrice.getPrice().compareTo(price) != 0) {
                ticketPrice.setPrice(price);
                holidayPricesChanged |= ticketPrice.getDayType() != DayType.WEEKDAY;
            }
        }
        for (Map.Entry<HolidayCell, BigDecimal> entry : newHolidayPrices.entrySet()) {
            HolidayCell cell = entry.getKey();
            BigDecimal price = entry.getValue();
            checkPrice(price);
            if (!isEditable(cell.seatType())) {
                throw coupleNotTypedIn();
            }
            Optional<TicketPrice> existing = ticketPriceRepository.findBySeatTypeAndFormatAndDayTypeAndTimeSlot(
                    cell.seatType(), cell.format(), DayType.HOLIDAY, cell.timeSlot());
            if (existing.isPresent()) {
                if (existing.get().getPrice().compareTo(price) != 0) {
                    existing.get().setPrice(price);
                    holidayPricesChanged = true;
                }
            } else {
                ticketPriceRepository.save(new TicketPrice(cell.seatType(), cell.format(), DayType.HOLIDAY, cell.timeSlot(), price));
                holidayPricesChanged = true;
            }
        }
        // Flush here so a database that refuses a HOLIDAY row fails inside this method, through the
        // repository's exception translation (DataIntegrityViolationException).
        ticketPriceRepository.flush();
        return holidayPricesChanged;
    }

    private static void checkPrice(BigDecimal price) {
        if (price == null || price.signum() < 0) {
            throw new BusinessException("Giá vé không hợp lệ");
        }
    }

    private static BusinessException coupleNotTypedIn() {
        return new BusinessException("Giá ghế đôi được tính tự động bằng "
                + COUPLE_VIP_SEATS + " lần giá ghế VIP, không nhập tay");
    }

    /**
     * Sets the current price on the free seats of the showtimes starting on these dates that have
     * not started yet. Seats a customer holds or has bought keep the price they were shown, and so
     * do the bookings; a hold that has run out but was not released yet counts as free, as it does
     * when a customer picks seats.
     * <p>
     * Call it outside a transaction, once the change it reacts to is committed: each showtime is
     * re-priced in its own short transaction, so a busy holiday never keeps many seat rows locked
     * at once.
     *
     * @return the number of seats whose price changed
     */
    public int repriceAvailableSeatsOn(Collection<LocalDate> dates) {
        LocalDateTime now = LocalDateTime.now(clock);
        TransactionTemplate perShowtime = transactionTemplate(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        int changed = 0;
        for (LocalDate date : new TreeSet<>(dates)) {
            LocalDateTime from = date.atStartOfDay().isAfter(now) ? date.atStartOfDay() : now;
            LocalDateTime to = date.plusDays(1).atStartOfDay();
            if (!from.isBefore(to)) {
                continue;
            }
            for (Showtime showtime : showtimeRepository.findActiveStartingBetween(from, to)) {
                Integer count = perShowtime.execute(status -> reprice(showtime));
                changed += count == null ? 0 : count;
            }
        }
        if (changed > 0) {
            log.info("Re-priced {} free seats on {}", changed, dates);
        }
        return changed;
    }

    /**
     * Re-prices the free seats of one showtime. The free seat ids are read without a lock, then
     * locked with {@link ShowtimeSeatRepository#findForUpdate} (the query a customer's hold uses, in
     * the same id order, so the two cannot deadlock) and checked again: a seat held in between is
     * left alone, and a hold that was waiting for the lock then takes the seat at its new price.
     */
    private int reprice(Showtime showtime) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Long> freeIds = showtimeSeatRepository.findFreeSeatIds(showtime.getId(), now);
        if (freeIds.isEmpty()) {
            return 0;
        }
        Map<SeatType, BigDecimal> priceByType = new EnumMap<>(SeatType.class);
        int changed = 0;
        for (ShowtimeSeat ss : showtimeSeatRepository.findForUpdate(showtime.getId(), freeIds)) {
            if (ShowtimeService.effectiveStatus(ss, now) != SeatStatus.AVAILABLE) {
                continue;
            }
            BigDecimal price = priceByType.computeIfAbsent(ss.getSeat().getType(),
                    type -> priceFor(type, showtime.getFormat(), showtime.getStartTime()));
            if (ss.getPrice().compareTo(price) != 0) {
                ss.setPrice(price);
                changed++;
            }
        }
        return changed;
    }

    private TransactionTemplate transactionTemplate(int propagation) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(propagation);
        return template;
    }

    /**
     * Creates every missing weekday and weekend price row for standard and VIP seats with a default
     * value, so every show has a price. Holiday rows are optional: a missing one uses the weekend price.
     */
    @Transactional
    public void ensureAllPricesExist(BigDecimal defaultPrice) {
        for (ScreenFormat format : ScreenFormat.values()) {
            for (DayType dayType : List.of(DayType.WEEKDAY, DayType.WEEKEND)) {
                for (TimeSlot slot : TimeSlot.values()) {
                    for (SeatType seatType : SeatType.values()) {
                        if (isEditable(seatType) && ticketPriceRepository.findBySeatTypeAndFormatAndDayTypeAndTimeSlot(
                                seatType, format, dayType, slot).isEmpty()) {
                            ticketPriceRepository.save(new TicketPrice(seatType, format, dayType, slot, defaultPrice));
                        }
                    }
                }
            }
        }
    }
}
