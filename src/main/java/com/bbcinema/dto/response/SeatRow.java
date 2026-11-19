package com.bbcinema.dto.response;

import java.util.List;

public record SeatRow(String label, List<SeatCell> cells) {
}
