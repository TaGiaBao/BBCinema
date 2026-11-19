package com.bbcinema.dto.response;

import com.bbcinema.entity.enums.SeatStatus;
import com.bbcinema.entity.enums.SeatType;

import java.math.BigDecimal;

/**
 * One seat of a seat map. {@code x} is its column on the room grid and {@code width} the number of
 * columns it takes (2 for a couple seat). {@code showtimeSeatId} is null for a seat that is not
 * on sale in this showtime (an aisle or a seat added after the showtime was created).
 */
public record SeatCell(
        Long showtimeSeatId,
        String label,
        int number,
        SeatType type,
        SeatStatus status,
        BigDecimal price,
        int x,
        int width) {

    public boolean isGap() {
        return showtimeSeatId == null;
    }

    public boolean isAvailable() {
        return status == SeatStatus.AVAILABLE;
    }
}
