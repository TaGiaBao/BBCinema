package com.bbcinema.dto.response;

import java.math.BigDecimal;

public record MovieRevenue(String movieTitle, BigDecimal revenue, long tickets) {
}
