package com.bbcinema.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record HoldSeatsRequest(
        @NotNull Long showtimeId,
        @NotEmpty(message = "Vui lòng chọn ít nhất một ghế") List<Long> showtimeSeatIds) {
}
