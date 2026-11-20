package com.bbcinema.service;

import com.bbcinema.entity.TicketPrice;
import com.bbcinema.entity.enums.DayType;
import com.bbcinema.entity.enums.ScreenFormat;
import com.bbcinema.entity.enums.SeatType;
import com.bbcinema.entity.enums.TimeSlot;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.repository.HolidayRepository;
import com.bbcinema.repository.ShowtimeRepository;
import com.bbcinema.repository.ShowtimeSeatRepository;
import com.bbcinema.repository.TicketPriceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PricingServiceTest {

    @Mock
    TicketPriceRepository repository;

    @Mock
    HolidayRepository holidayRepository;

    @Mock
    ShowtimeRepository showtimeRepository;

    @Mock
    ShowtimeSeatRepository showtimeSeatRepository;

    @Mock
    PlatformTransactionManager transactionManager;

    PricingService service;

    /** "Now" for these tests: Friday 20/11/2026, 10:00. */
    static final Clock CLOCK = Clock.fixed(LocalDateTime.of(2026, 11, 20, 10, 0).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant(),
            ZoneId.of("Asia/Ho_Chi_Minh"));

    @BeforeEach
    void setUp() {
        service = new PricingService(repository, holidayRepository, showtimeRepository, showtimeSeatRepository, CLOCK, transactionManager);
    }

    @Test
    void saturdayAndSundayAreWeekend() {
        assertThat(PricingService.dayTypeOf(LocalDateTime.of(2026, 11, 21, 10, 0))).isEqualTo(DayType.WEEKEND);
        assertThat(PricingService.dayTypeOf(LocalDateTime.of(2026, 11, 22, 10, 0))).isEqualTo(DayType.WEEKEND);
        assertThat(PricingService.dayTypeOf(LocalDateTime.of(2026, 11, 20, 23, 0))).isEqualTo(DayType.WEEKDAY);
    }

    @Test
    void fivePmStartsTheEveningSlot() {
        assertThat(PricingService.timeSlotOf(LocalDateTime.of(2026, 11, 20, 16, 59))).isEqualTo(TimeSlot.BEFORE_17);
        assertThat(PricingService.timeSlotOf(LocalDateTime.of(2026, 11, 20, 17, 0))).isEqualTo(TimeSlot.FROM_17);
    }

    @Test
    void findsPriceForSeatFormatDayAndTime() {
        TicketPrice price = new TicketPrice(SeatType.VIP, ScreenFormat.TWO_D, DayType.WEEKEND, TimeSlot.FROM_17, BigDecimal.valueOf(100_000));
        when(repository.findBySeatTypeAndFormatAndDayTypeAndTimeSlot(SeatType.VIP, ScreenFormat.TWO_D, DayType.WEEKEND, TimeSlot.FROM_17))
                .thenReturn(Optional.of(price));

        BigDecimal result = service.priceFor(SeatType.VIP, ScreenFormat.TWO_D, LocalDateTime.of(2026, 11, 21, 19, 30));

        assertThat(result).isEqualByComparingTo("100000");
    }

    @Test
    void missingPriceIsReported() {
        when(repository.findBySeatTypeAndFormatAndDayTypeAndTimeSlot(any(), any(), any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.priceFor(SeatType.COUPLE, ScreenFormat.THREE_D, LocalDateTime.of(2026, 11, 20, 9, 0)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Chưa cấu hình giá vé");
    }

    @Test
    void negativePriceIsRejected() {
        assertThatThrownBy(() -> service.updatePrices(Map.of(1L, BigDecimal.valueOf(-1))))
                .isInstanceOf(BusinessException.class);
        verify(repository, never()).findById(any());
    }

    @Test
    void updatesPrices() {
        TicketPrice price = new TicketPrice(SeatType.STANDARD, ScreenFormat.TWO_D, DayType.WEEKDAY, TimeSlot.BEFORE_17, BigDecimal.TEN);
        when(repository.findById(7L)).thenReturn(Optional.of(price));

        service.updatePrices(Map.of(7L, BigDecimal.valueOf(65_000)));

        assertThat(price.getPrice()).isEqualByComparingTo("65000");
    }

    @Test
    void createsAllMissingPriceRows() {
        when(repository.findBySeatTypeAndFormatAndDayTypeAndTimeSlot(any(), any(), any(), any())).thenReturn(Optional.empty());

        service.ensureAllPricesExist(BigDecimal.valueOf(80_000));

        // 2 formats x 2 day types x 2 time slots x 2 typed-in seat types (couple is derived)
        verify(repository, times(16)).save(any(TicketPrice.class));
        verify(repository, never()).save(argThat(p -> p.getSeatType() == SeatType.COUPLE));
    }

    @Test
    void coupleSeatCostsTwoVipSeats() {
        TicketPrice vip = new TicketPrice(SeatType.VIP, ScreenFormat.THREE_D, DayType.WEEKEND, TimeSlot.FROM_17, BigDecimal.valueOf(120_000));
        when(repository.findBySeatTypeAndFormatAndDayTypeAndTimeSlot(SeatType.VIP, ScreenFormat.THREE_D, DayType.WEEKEND, TimeSlot.FROM_17))
                .thenReturn(Optional.of(vip));

        BigDecimal result = service.priceFor(SeatType.COUPLE, ScreenFormat.THREE_D, LocalDateTime.of(2026, 11, 22, 19, 30));

        assertThat(result).isEqualByComparingTo("240000");
        verify(repository, never()).findBySeatTypeAndFormatAndDayTypeAndTimeSlot(eq(SeatType.COUPLE), any(), any(), any());
    }

    @Test
    void priceTableAddsDerivedCoupleRowAfterVipAndDropsOldCoupleRows() {
        TicketPrice standard = new TicketPrice(SeatType.STANDARD, ScreenFormat.TWO_D, DayType.WEEKDAY, TimeSlot.BEFORE_17, BigDecimal.valueOf(60_000));
        TicketPrice vip = new TicketPrice(SeatType.VIP, ScreenFormat.TWO_D, DayType.WEEKDAY, TimeSlot.BEFORE_17, BigDecimal.valueOf(70_000));
        TicketPrice oldCouple = new TicketPrice(SeatType.COUPLE, ScreenFormat.TWO_D, DayType.WEEKDAY, TimeSlot.BEFORE_17, BigDecimal.valueOf(130_000));
        when(repository.findAllByOrderByFormatAscDayTypeAscTimeSlotAscSeatTypeAsc()).thenReturn(List.of(standard, vip, oldCouple));

        List<TicketPrice> table = service.findAll();

        assertThat(table).extracting(TicketPrice::getSeatType).containsExactly(SeatType.STANDARD, SeatType.VIP, SeatType.COUPLE);
        assertThat(table.get(2).getPrice()).isEqualByComparingTo("140000");
        assertThat(table.get(2).getId()).isNull();
    }

    @Test
    void couplePriceCannotBeTypedIn() {
        TicketPrice oldCouple = new TicketPrice(SeatType.COUPLE, ScreenFormat.TWO_D, DayType.WEEKDAY, TimeSlot.BEFORE_17, BigDecimal.valueOf(130_000));
        when(repository.findById(9L)).thenReturn(Optional.of(oldCouple));

        assertThatThrownBy(() -> service.updatePrices(Map.of(9L, BigDecimal.valueOf(1))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("tự động");
        assertThat(oldCouple.getPrice()).isEqualByComparingTo("130000");
    }

    // ------------------------------------------------------------------ holidays

    private static final LocalDate NATIONAL_DAY = LocalDate.of(2027, 9, 2); // a Thursday

    @Test
    void holidayOnAWeekdayUsesTheHolidayPrice() {
        when(holidayRepository.existsByDate(NATIONAL_DAY)).thenReturn(true);
        when(repository.findBySeatTypeAndFormatAndDayTypeAndTimeSlot(SeatType.STANDARD, ScreenFormat.TWO_D, DayType.HOLIDAY, TimeSlot.FROM_17))
                .thenReturn(Optional.of(new TicketPrice(SeatType.STANDARD, ScreenFormat.TWO_D, DayType.HOLIDAY, TimeSlot.FROM_17, BigDecimal.valueOf(99_000))));

        BigDecimal result = service.priceFor(SeatType.STANDARD, ScreenFormat.TWO_D, NATIONAL_DAY.atTime(19, 0));

        assertThat(service.dayTypeFor(NATIONAL_DAY.atTime(19, 0))).isEqualTo(DayType.HOLIDAY);
        assertThat(result).isEqualByComparingTo("99000");
        verify(repository, never()).findBySeatTypeAndFormatAndDayTypeAndTimeSlot(any(), any(), eq(DayType.WEEKDAY), any());
    }

    @Test
    void holidayIsDecidedByTheStartDate() {
        when(holidayRepository.existsByDate(any())).thenAnswer(call -> NATIONAL_DAY.equals(call.getArgument(0)));

        assertThat(service.dayTypeFor(NATIONAL_DAY.minusDays(1).atTime(23, 30))).isEqualTo(DayType.WEEKDAY);
        assertThat(service.dayTypeFor(NATIONAL_DAY.atTime(0, 15))).isEqualTo(DayType.HOLIDAY);
    }

    @Test
    void holidayOnASundayStillUsesTheHolidayPrice() {
        LocalDate sunday = LocalDate.of(2027, 5, 2);
        when(holidayRepository.existsByDate(sunday)).thenReturn(true);

        assertThat(service.dayTypeFor(sunday.atTime(10, 0))).isEqualTo(DayType.HOLIDAY);
    }

    @Test
    void missingHolidayPriceFallsBackToTheWeekendPrice() {
        when(holidayRepository.existsByDate(NATIONAL_DAY)).thenReturn(true);
        when(repository.findBySeatTypeAndFormatAndDayTypeAndTimeSlot(SeatType.VIP, ScreenFormat.THREE_D, DayType.HOLIDAY, TimeSlot.BEFORE_17))
                .thenReturn(Optional.empty());
        when(repository.findBySeatTypeAndFormatAndDayTypeAndTimeSlot(SeatType.VIP, ScreenFormat.THREE_D, DayType.WEEKEND, TimeSlot.BEFORE_17))
                .thenReturn(Optional.of(new TicketPrice(SeatType.VIP, ScreenFormat.THREE_D, DayType.WEEKEND, TimeSlot.BEFORE_17, BigDecimal.valueOf(110_000))));

        assertThat(service.priceFor(SeatType.VIP, ScreenFormat.THREE_D, NATIONAL_DAY.atTime(9, 0))).isEqualByComparingTo("110000");
    }

    @Test
    void coupleSeatOnAHolidayCostsTwoHolidayVipSeats() {
        when(holidayRepository.existsByDate(NATIONAL_DAY)).thenReturn(true);
        when(repository.findBySeatTypeAndFormatAndDayTypeAndTimeSlot(SeatType.VIP, ScreenFormat.TWO_D, DayType.HOLIDAY, TimeSlot.FROM_17))
                .thenReturn(Optional.of(new TicketPrice(SeatType.VIP, ScreenFormat.TWO_D, DayType.HOLIDAY, TimeSlot.FROM_17, BigDecimal.valueOf(110_000))));

        assertThat(service.priceFor(SeatType.COUPLE, ScreenFormat.TWO_D, NATIONAL_DAY.atTime(20, 0))).isEqualByComparingTo("220000");
    }

    @Test
    void priceTableShowsTheWeekendPriceForHolidayPricesNotSet() {
        TicketPrice weekendVip = new TicketPrice(SeatType.VIP, ScreenFormat.TWO_D, DayType.WEEKEND, TimeSlot.FROM_17, BigDecimal.valueOf(100_000));
        TicketPrice weekendStandard = new TicketPrice(SeatType.STANDARD, ScreenFormat.TWO_D, DayType.WEEKEND, TimeSlot.FROM_17, BigDecimal.valueOf(90_000));
        TicketPrice holidayStandard = new TicketPrice(SeatType.STANDARD, ScreenFormat.TWO_D, DayType.HOLIDAY, TimeSlot.FROM_17, BigDecimal.valueOf(99_000));
        when(repository.findAllByOrderByFormatAscDayTypeAscTimeSlotAscSeatTypeAsc()).thenReturn(List.of(holidayStandard, weekendStandard, weekendVip));

        List<TicketPrice> holidayRows = service.findAll().stream().filter(p -> p.getDayType() == DayType.HOLIDAY).toList();

        assertThat(holidayRows).extracting(TicketPrice::getSeatType).containsExactly(SeatType.STANDARD, SeatType.VIP, SeatType.COUPLE);
        assertThat(holidayRows.get(0).isWeekendFallback()).isFalse();
        assertThat(holidayRows.get(0).getPrice()).isEqualByComparingTo("99000");
        assertThat(holidayRows.get(1).isWeekendFallback()).isTrue();
        assertThat(holidayRows.get(1).getPrice()).isEqualByComparingTo("100000");
        assertThat(holidayRows.get(2).getPrice()).isEqualByComparingTo("200000");
        assertThat(holidayRows.get(2).isWeekendFallback()).isTrue();
    }

    @Test
    void holidayPriceTypedInForTheFirstTimeCreatesTheRowAndRepricesHolidayShows() {
        when(repository.findBySeatTypeAndFormatAndDayTypeAndTimeSlot(SeatType.VIP, ScreenFormat.TWO_D, DayType.HOLIDAY, TimeSlot.FROM_17))
                .thenReturn(Optional.empty());
        when(holidayRepository.findDatesFrom(LocalDate.of(2026, 11, 20))).thenReturn(List.of());

        service.updatePrices(Map.of(), Map.of(new PricingService.HolidayCell(SeatType.VIP, ScreenFormat.TWO_D, TimeSlot.FROM_17),
                BigDecimal.valueOf(120_000)));

        verify(repository).save(argThat(p -> p.getDayType() == DayType.HOLIDAY && p.getSeatType() == SeatType.VIP
                && p.getPrice().compareTo(BigDecimal.valueOf(120_000)) == 0));
        verify(holidayRepository).findDatesFrom(LocalDate.of(2026, 11, 20));
    }

    @Test
    void coupleHolidayPriceCannotBeTypedIn() {
        assertThatThrownBy(() -> service.updatePrices(Map.of(), Map.of(
                new PricingService.HolidayCell(SeatType.COUPLE, ScreenFormat.TWO_D, TimeSlot.FROM_17), BigDecimal.ONE)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("tự động");
        verify(repository, never()).save(any());
    }

    @Test
    void createsNoHolidayRowsByDefault() {
        when(repository.findBySeatTypeAndFormatAndDayTypeAndTimeSlot(any(), any(), any(), any())).thenReturn(Optional.empty());

        service.ensureAllPricesExist(BigDecimal.valueOf(80_000));

        verify(repository, never()).save(argThat(p -> p.getDayType() == DayType.HOLIDAY));
    }

    // ------------------------------------------------------------------ re-pricing only when holiday shows are affected

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);

    @Test
    void weekdayPriceChangeDoesNotRepriceHolidayShows() {
        TicketPrice weekday = new TicketPrice(SeatType.STANDARD, ScreenFormat.TWO_D, DayType.WEEKDAY, TimeSlot.FROM_17, BigDecimal.valueOf(70_000));
        when(repository.findById(1L)).thenReturn(Optional.of(weekday));

        assertThat(service.updatePrices(Map.of(1L, BigDecimal.valueOf(75_000)))).isZero();

        assertThat(weekday.getPrice()).isEqualByComparingTo("75000");
        verify(holidayRepository, never()).findDatesFrom(any());
    }

    @Test
    void unchangedHolidayAndWeekendPricesDoNotReprice() {
        TicketPrice weekend = new TicketPrice(SeatType.STANDARD, ScreenFormat.TWO_D, DayType.WEEKEND, TimeSlot.FROM_17, BigDecimal.valueOf(90_000));
        TicketPrice holiday = new TicketPrice(SeatType.STANDARD, ScreenFormat.TWO_D, DayType.HOLIDAY, TimeSlot.FROM_17, BigDecimal.valueOf(99_000));
        when(repository.findById(2L)).thenReturn(Optional.of(weekend));
        when(repository.findById(3L)).thenReturn(Optional.of(holiday));

        service.updatePrices(Map.of(2L, BigDecimal.valueOf(90_000), 3L, BigDecimal.valueOf(99_000)));

        verify(holidayRepository, never()).findDatesFrom(any());
    }

    @Test
    void weekendPriceChangeRepricesHolidayShowsBecauseHolidaysFallBackToIt() {
        TicketPrice weekend = new TicketPrice(SeatType.VIP, ScreenFormat.TWO_D, DayType.WEEKEND, TimeSlot.FROM_17, BigDecimal.valueOf(100_000));
        when(repository.findById(2L)).thenReturn(Optional.of(weekend));
        when(holidayRepository.findDatesFrom(TODAY)).thenReturn(List.of());

        service.updatePrices(Map.of(2L, BigDecimal.valueOf(105_000)));

        verify(holidayRepository).findDatesFrom(TODAY);
    }

    @Test
    void emptyHolidayPriceDeletesTheRowAndReprices() {
        TicketPrice holiday = new TicketPrice(SeatType.STANDARD, ScreenFormat.TWO_D, DayType.HOLIDAY, TimeSlot.FROM_17, BigDecimal.valueOf(99_000));
        when(repository.findById(3L)).thenReturn(Optional.of(holiday));
        when(holidayRepository.findDatesFrom(TODAY)).thenReturn(List.of());
        Map<Long, BigDecimal> prices = new HashMap<>();
        prices.put(3L, null);

        service.updatePrices(prices);

        verify(repository).delete(holiday);
        verify(holidayRepository).findDatesFrom(TODAY);
    }

    @Test
    void emptyWeekdayOrWeekendPriceIsRejected() {
        for (DayType day : List.of(DayType.WEEKDAY, DayType.WEEKEND)) {
            TicketPrice row = new TicketPrice(SeatType.STANDARD, ScreenFormat.TWO_D, day, TimeSlot.FROM_17, BigDecimal.valueOf(80_000));
            when(repository.findById(4L)).thenReturn(Optional.of(row));
            Map<Long, BigDecimal> prices = new HashMap<>();
            prices.put(4L, null);

            assertThatThrownBy(() -> service.updatePrices(prices))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Giá vé phải là số");
        }
        verify(repository, never()).delete(any());
    }
}
