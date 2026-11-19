package com.bbcinema.dto.response;

import com.bbcinema.entity.enums.SeatStatus;
import com.bbcinema.entity.enums.SeatType;

import java.math.BigDecimal;

/**
 * One cell of a seat map. {@code showtimeSeatId} is null for a gap in the layout
 * (an inactive seat), which is drawn as empty space.
 */
public record SeatCell(
        Long showtimeSeatId,
        String label,
        int number,
        SeatType type,
        SeatStatus status,
        BigDecimal price) {

    public boolean isGap() {
        return showtimeSeatId == null;
    }

    public boolean isAvailable() {
        return status == SeatStatus.AVAILABLE;
    }
}
