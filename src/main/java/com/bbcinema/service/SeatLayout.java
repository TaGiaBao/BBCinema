package com.bbcinema.service;

import com.bbcinema.entity.Seat;

import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Where each seat of a room sits on the layout grid. Seats placed with the layout editor have their
 * own position; older seats without one are laid out as before: one grid row per row letter
 * (A nearest the screen) and seats side by side in number order.
 */
public final class SeatLayout {

    /** Largest grid the editor allows. */
    public static final int MAX_COLUMNS = 40;
    public static final int MAX_ROWS = 30;

    public record Position(int x, int y) {
    }

    private SeatLayout() {
    }

    /** Position of every seat (keyed by the seat object itself). */
    public static Map<Seat, Position> positions(List<Seat> seats) {
        Map<Seat, Position> result = new IdentityHashMap<>();
        List<String> letters = List.copyOf(new TreeSet<>(seats.stream().map(Seat::getRowLabel).toList()));
        Map<String, Integer> nextX = new HashMap<>();
        seats.stream()
                .sorted(Comparator.comparing(Seat::getRowLabel).thenComparingInt(Seat::getNumber))
                .forEach(seat -> {
                    if (seat.getGridX() != null && seat.getGridY() != null) {
                        result.put(seat, new Position(seat.getGridX(), seat.getGridY()));
                    } else {
                        int x = nextX.getOrDefault(seat.getRowLabel(), 0);
                        result.put(seat, new Position(x, letters.indexOf(seat.getRowLabel())));
                        nextX.put(seat.getRowLabel(), x + seat.getWidth());
                    }
                });
        return result;
    }

    /** Number of grid columns needed to show all seats. */
    public static int columns(List<Seat> seats, Map<Seat, Position> positions) {
        return seats.stream().mapToInt(s -> positions.get(s).x() + s.getWidth()).max().orElse(0);
    }

    /** Number of grid rows needed to show all seats. */
    public static int rows(List<Seat> seats, Map<Seat, Position> positions) {
        return seats.stream().mapToInt(s -> positions.get(s).y() + 1).max().orElse(0);
    }
}
