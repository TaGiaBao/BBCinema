package com.bbcinema.exception;

import java.util.List;

/** Thrown when someone else has already held or bought one of the requested seats. */
public class SeatUnavailableException extends BusinessException {

    private final List<String> seatLabels;

    public SeatUnavailableException(List<String> seatLabels) {
        super("Ghế " + String.join(", ", seatLabels) + " vừa có người chọn. Vui lòng chọn ghế khác.");
        this.seatLabels = List.copyOf(seatLabels);
    }

    public List<String> getSeatLabels() {
        return seatLabels;
    }
}
