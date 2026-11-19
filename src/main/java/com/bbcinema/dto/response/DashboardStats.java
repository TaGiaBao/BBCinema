package com.bbcinema.dto.response;

import java.math.BigDecimal;

public record DashboardStats(
        BigDecimal revenueToday,
        long bookingsToday,
        BigDecimal revenueThisMonth,
        long bookingsThisMonth,
        long members,
        long moviesShowing) {
}
