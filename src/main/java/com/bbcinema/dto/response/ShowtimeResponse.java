package com.bbcinema.dto.response;

import com.bbcinema.entity.Showtime;

import java.time.LocalDateTime;

public record ShowtimeResponse(
        Long id,
        Long movieId,
        String movieTitle,
        Long cinemaId,
        String cinemaName,
        String roomName,
        LocalDateTime startTime,
        LocalDateTime endTime,
        String format,
        String language,
        boolean special) {

    public static ShowtimeResponse from(Showtime s) {
        return new ShowtimeResponse(
                s.getId(),
                s.getMovie().getId(),
                s.getMovie().getTitle(),
                s.getRoom().getCinema().getId(),
                s.getRoom().getCinema().getName(),
                s.getRoom().getName(),
                s.getStartTime(),
                s.getEndTime(),
                s.getFormat().getLabel(),
                s.getLanguage().getLabel(),
                s.isSpecial());
    }
}
