package com.bbcinema.dto.response;

import com.bbcinema.entity.Booking;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record BookingResponse(
        String code,
        String status,
        String movieTitle,
        String cinemaName,
        String roomName,
        LocalDateTime startTime,
        String format,
        List<String> seats,
        List<ComboLine> combos,
        BigDecimal ticketAmount,
        BigDecimal comboAmount,
        BigDecimal promotionDiscount,
        int pointsUsed,
        BigDecimal totalAmount,
        LocalDateTime expiresAt,
        LocalDateTime paidAt) {

    public record ComboLine(Long comboId, String name, int quantity, BigDecimal unitPrice) {
    }

    public static BookingResponse from(Booking b) {
        var showtime = b.getShowtime();
        List<String> seats = b.getSeatLabels().isEmpty() ? List.of() : List.of(b.getSeatLabels().split(", "));
        List<ComboLine> combos = b.getCombos().stream()
                .map(c -> new ComboLine(c.getCombo().getId(), c.getCombo().getName(), c.getQuantity(), c.getUnitPrice()))
                .toList();
        return new BookingResponse(
                b.getCode(),
                b.getStatus().name(),
                showtime.getMovie().getTitle(),
                showtime.getRoom().getCinema().getName(),
                showtime.getRoom().getName(),
                showtime.getStartTime(),
                showtime.getFormat().getLabel(),
                seats,
                combos,
                b.getTicketAmount(),
                b.getComboAmount(),
                b.getPromotionDiscount(),
                b.getPointsUsed(),
                b.getTotalAmount(),
                b.getExpiresAt(),
                b.getPaidAt());
    }
}
