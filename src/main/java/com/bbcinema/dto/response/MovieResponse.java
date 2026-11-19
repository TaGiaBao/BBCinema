package com.bbcinema.dto.response;

import com.bbcinema.entity.Genre;
import com.bbcinema.entity.Movie;

import java.time.LocalDate;
import java.util.List;

public record MovieResponse(
        Long id,
        String title,
        String slug,
        String director,
        String actors,
        String description,
        int durationMinutes,
        String ageRating,
        LocalDate releaseDate,
        String language,
        String posterUrl,
        String trailerUrl,
        String status,
        List<String> genres) {

    public static MovieResponse from(Movie m) {
        return new MovieResponse(
                m.getId(),
                m.getTitle(),
                m.getSlug(),
                m.getDirector(),
                m.getActors(),
                m.getDescription(),
                m.getDurationMinutes(),
                m.getAgeRating().name(),
                m.getReleaseDate(),
                m.getLanguage(),
                m.getPosterPath(),
                m.getTrailerUrl(),
                m.getStatus().name(),
                m.getGenres().stream().map(Genre::getName).toList());
    }
}
