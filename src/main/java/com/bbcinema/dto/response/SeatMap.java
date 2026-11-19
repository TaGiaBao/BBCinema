package com.bbcinema.dto.response;

import java.util.List;

public record SeatMap(Long showtimeId, List<SeatRow> rows, int columns, long availableCount, long totalCount) {
}
