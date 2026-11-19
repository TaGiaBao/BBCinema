package com.bbcinema.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DailyRevenue(LocalDate date, BigDecimal revenue, long bookings) {
}
