package com.bbcinema.service;

import com.bbcinema.entity.TicketPrice;
import com.bbcinema.entity.enums.DayType;
import com.bbcinema.entity.enums.ScreenFormat;
import com.bbcinema.entity.enums.SeatType;
import com.bbcinema.entity.enums.TimeSlot;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.repository.TicketPriceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PricingServiceTest {

    @Mock
    TicketPriceRepository repository;

    @InjectMocks
    PricingService service;

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

        // 2 formats x 2 day types x 2 time slots x 3 seat types
        verify(repository, times(24)).save(any(TicketPrice.class));
    }
}
