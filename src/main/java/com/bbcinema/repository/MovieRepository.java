package com.bbcinema.repository;

import com.bbcinema.entity.Movie;
import com.bbcinema.entity.enums.MovieStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface MovieRepository extends JpaRepository<Movie, Long> {

    Optional<Movie> findBySlug(String slug);

    boolean existsBySlug(String slug);

    List<Movie> findByStatusOrderByReleaseDateDesc(MovieStatus status);

    @Query("""
            select m from Movie m
            where (:keyword is null or lower(m.title) like lower(concat('%', :keyword, '%')))
              and (:status is null or m.status = :status)
              and (:genreId is null or exists (select g from m.genres g where g.id = :genreId))
            order by m.releaseDate desc, m.id desc
            """)
    Page<Movie> search(String keyword, MovieStatus status, Long genreId, Pageable pageable);

    /** Movies that have at least one special screening in the cinema from now on. */
    @Query("""
            select distinct m from Showtime s join s.movie m
            where s.special = true and s.cancelled = false
              and s.room.cinema.id = :cinemaId and s.startTime >= :from
            order by m.title
            """)
    List<Movie> findWithSpecialShowtimes(Long cinemaId, LocalDateTime from);
}
