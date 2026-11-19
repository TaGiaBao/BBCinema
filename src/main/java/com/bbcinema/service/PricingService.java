package com.bbcinema.service;

import com.bbcinema.entity.TicketPrice;
import com.bbcinema.entity.enums.DayType;
import com.bbcinema.entity.enums.ScreenFormat;
import com.bbcinema.entity.enums.SeatType;
import com.bbcinema.entity.enums.TimeSlot;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.repository.TicketPriceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * Looks up ticket prices. A price depends on four things: seat type, 2D/3D, weekday or weekend,
 * and whether the show starts before or from 17:00.
 */
@Service
@RequiredArgsConstructor
public class PricingService {

    static final LocalTime EVENING_START = LocalTime.of(17, 0);

    private final TicketPriceRepository ticketPriceRepository;

    public static DayType dayTypeOf(LocalDateTime start) {
        DayOfWeek day = start.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY ? DayType.WEEKEND : DayType.WEEKDAY;
    }

    public static TimeSlot timeSlotOf(LocalDateTime start) {
        return start.toLocalTime().isBefore(EVENING_START) ? TimeSlot.BEFORE_17 : TimeSlot.FROM_17;
    }

    @Transactional(readOnly = true)
    public BigDecimal priceFor(SeatType seatType, ScreenFormat format, LocalDateTime start) {
        return ticketPriceRepository
                .findBySeatTypeAndFormatAndDayTypeAndTimeSlot(seatType, format, dayTypeOf(start), timeSlotOf(start))
                .map(TicketPrice::getPrice)
                .orElseThrow(() -> new BusinessException(
                        "Chưa cấu hình giá vé cho " + seatType.getLabel() + " " + format.getLabel()
                                + ", " + dayTypeOf(start).getLabel() + ", " + timeSlotOf(start).getLabel()));
    }

    @Transactional(readOnly = true)
    public List<TicketPrice> findAll() {
        return ticketPriceRepository.findAllByOrderByFormatAscDayTypeAscTimeSlotAscSeatTypeAsc();
    }

    /** Saves the prices typed in the admin price table: price id -> new price. */
    @Transactional
    public void updatePrices(Map<Long, BigDecimal> prices) {
        prices.forEach((id, price) -> {
            if (price == null || price.signum() < 0) {
                throw new BusinessException("Giá vé không hợp lệ");
            }
            TicketPrice ticketPrice = ticketPriceRepository.findById(id)
                    .orElseThrow(() -> new BusinessException("Không tìm thấy dòng giá #" + id));
            ticketPrice.setPrice(price);
        });
    }

    /** Creates every missing price row with a default value, so the price table is always complete. */
    @Transactional
    public void ensureAllPricesExist(BigDecimal defaultPrice) {
        for (ScreenFormat format : ScreenFormat.values()) {
            for (DayType dayType : DayType.values()) {
                for (TimeSlot slot : TimeSlot.values()) {
                    for (SeatType seatType : SeatType.values()) {
                        if (ticketPriceRepository.findBySeatTypeAndFormatAndDayTypeAndTimeSlot(
                                seatType, format, dayType, slot).isEmpty()) {
                            ticketPriceRepository.save(new TicketPrice(seatType, format, dayType, slot, defaultPrice));
                        }
                    }
                }
            }
        }
    }
}
