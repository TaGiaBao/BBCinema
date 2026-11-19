package com.bbcinema.service;

import com.bbcinema.entity.Seat;
import com.bbcinema.entity.enums.SeatType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SeatLayoutTest {

    private static Seat seat(String row, int number, SeatType type, Integer x, Integer y) {
        Seat seat = new Seat(row, number, type);
        seat.setGridX(x);
        seat.setGridY(y);
        return seat;
    }

    @Test
    void seatsWithoutPositionAreLaidOutByRowAndNumber() {
        Seat a1 = seat("A", 1, SeatType.STANDARD, null, null);
        Seat a2 = seat("A", 2, SeatType.STANDARD, null, null);
        Seat c1 = seat("C", 1, SeatType.COUPLE, null, null);
        Seat c2 = seat("C", 2, SeatType.COUPLE, null, null);

        Map<Seat, SeatLayout.Position> pos = SeatLayout.positions(List.of(c2, a2, c1, a1));

        assertThat(pos.get(a1)).isEqualTo(new SeatLayout.Position(0, 0));
        assertThat(pos.get(a2)).isEqualTo(new SeatLayout.Position(1, 0));
        // Row C is the second row letter in use; couple seats are two columns wide.
        assertThat(pos.get(c1)).isEqualTo(new SeatLayout.Position(0, 1));
        assertThat(pos.get(c2)).isEqualTo(new SeatLayout.Position(2, 1));
        assertThat(SeatLayout.columns(List.of(a1, a2, c1, c2), pos)).isEqualTo(4);
        assertThat(SeatLayout.rows(List.of(a1, a2, c1, c2), pos)).isEqualTo(2);
    }

    @Test
    void placedSeatsKeepTheirPosition() {
        Seat s = seat("K", 3, SeatType.VIP, 7, 4);
        assertThat(SeatLayout.positions(List.of(s)).get(s)).isEqualTo(new SeatLayout.Position(7, 4));
    }
}
