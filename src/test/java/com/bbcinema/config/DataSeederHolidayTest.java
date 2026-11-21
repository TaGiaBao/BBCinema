package com.bbcinema.config;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.MonthDay;

import static org.assertj.core.api.Assertions.assertThat;

class DataSeederHolidayTest {

    @Test
    void demoHolidayPriceIsWeekendPlusTenPercentRoundedToAThousand() {
        assertThat(DataSeeder.holidayPriceFrom(BigDecimal.valueOf(90_000))).isEqualByComparingTo("99000");
        assertThat(DataSeeder.holidayPriceFrom(BigDecimal.valueOf(110_000))).isEqualByComparingTo("121000");
        assertThat(DataSeeder.holidayPriceFrom(BigDecimal.valueOf(95_500))).isEqualByComparingTo("105000"); // 105,050
        assertThat(DataSeeder.holidayPriceFrom(BigDecimal.valueOf(85_000))).isEqualByComparingTo("94000"); // 93,500 rounds up
    }

    @Test
    void onlySolarCalendarHolidaysAreSeeded() {
        assertThat(DataSeeder.FIXED_HOLIDAYS.keySet())
                .containsExactly(MonthDay.of(1, 1), MonthDay.of(4, 30), MonthDay.of(5, 1), MonthDay.of(9, 2));
    }
}
