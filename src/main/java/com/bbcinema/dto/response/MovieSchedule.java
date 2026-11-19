package com.bbcinema.dto.response;

import com.bbcinema.entity.Movie;
import com.bbcinema.entity.Showtime;

import java.util.List;

/** A movie with its showtimes on one day, for the "schedule by cinema" page. */
public record MovieSchedule(Movie movie, List<Showtime> showtimes) {
}
