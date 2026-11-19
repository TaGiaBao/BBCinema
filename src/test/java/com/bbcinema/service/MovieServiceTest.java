package com.bbcinema.service;

import com.bbcinema.TestFixtures;
import com.bbcinema.entity.Movie;
import com.bbcinema.entity.enums.AgeRating;
import com.bbcinema.entity.enums.MovieStatus;
import com.bbcinema.repository.MovieRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MovieServiceTest {

    private final MovieRepository repository = mock(MovieRepository.class);
    private final MovieService service = new MovieService(repository, null, null, null, TestFixtures.clock());

    private static Movie upcoming(long id, LocalDate release) {
        Movie m = TestFixtures.movie(id, 100, AgeRating.P);
        m.setStatus(MovieStatus.COMING_SOON);
        m.setReleaseDate(release);
        return m;
    }

    @Test
    void featuredComingSoonPutsSoonestFirstAndLimitsTheList() {
        LocalDate d = LocalDate.of(2026, 12, 1);
        // The repository returns the newest release first; the featured list wants the soonest first.
        when(repository.findByStatusOrderByReleaseDateDesc(MovieStatus.COMING_SOON)).thenReturn(List.of(
                upcoming(1, d.plusDays(30)), upcoming(2, null), upcoming(3, d.plusDays(5)),
                upcoming(4, d), upcoming(5, d.plusDays(10)), upcoming(6, d.plusDays(20))));

        List<Movie> picks = service.featuredComingSoon(null);

        assertThat(picks).extracting(Movie::getId).containsExactly(4L, 3L, 5L, 6L);
    }

    @Test
    void featuredComingSoonLeavesOutTheMovieOnScreenAndPutsUndatedLast() {
        LocalDate d = LocalDate.of(2026, 12, 1);
        when(repository.findByStatusOrderByReleaseDateDesc(MovieStatus.COMING_SOON)).thenReturn(List.of(
                upcoming(1, null), upcoming(2, d), upcoming(3, d.plusDays(1))));

        assertThat(service.featuredComingSoon(2L)).extracting(Movie::getId).containsExactly(3L, 1L);
    }
}
