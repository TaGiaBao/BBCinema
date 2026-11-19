package com.bbcinema.dto.response;

import java.util.List;

/**
 * One line of the room grid. {@code label} is the row letter shown at both ends (empty for a line
 * with no seats, which is drawn as a walkway between blocks of seats).
 */
public record SeatRow(String label, List<SeatCell> cells) {
}
