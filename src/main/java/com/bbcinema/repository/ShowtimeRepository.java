package com.bbcinema.repository;

import com.bbcinema.entity.Showtime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ShowtimeRepository extends JpaRepository<Showtime, Long> {

    @Query("""
            select s from Showtime s join fetch s.movie join fetch s.room r join fetch r.cinema
            where s.id = :id
            """)
    Optional<Showtime> findDetailedById(Long id);

    @Query("""
            select s from Showtime s join fetch s.movie join fetch s.room r join fetch r.cinema
            where s.movie.id = :movieId and r.cinema.id = :cinemaId and s.cancelled = false
              and s.startTime >= :from and s.startTime < :to
            order by s.startTime
            """)
    List<Showtime> findForMovieInCinema(Long movieId, Long cinemaId, LocalDateTime from, LocalDateTime to);

    @Query("""
            select s from Showtime s join fetch s.movie m join fetch s.room r join fetch r.cinema
            where r.cinema.id = :cinemaId and s.cancelled = false
              and s.startTime >= :from and s.startTime < :to
            order by m.title, s.startTime
            """)
    List<Showtime> findForCinema(Long cinemaId, LocalDateTime from, LocalDateTime to);

    /** Showtimes in the same room whose time range overlaps [start, end). */
    @Query("""
            select s from Showtime s join fetch s.movie
            where s.room.id = :roomId and s.cancelled = false
              and s.startTime < :end and s.endTime > :start
              and (:excludeId is null or s.id <> :excludeId)
            """)
    List<Showtime> findOverlapping(Long roomId, LocalDateTime start, LocalDateTime end, Long excludeId);

    @Query(value = """
            select s from Showtime s join fetch s.movie join fetch s.room r join fetch r.cinema
            where (:cinemaId is null or r.cinema.id = :cinemaId)
              and (:movieId is null or s.movie.id = :movieId)
              and s.startTime >= :from and s.startTime < :to
            order by s.startTime
            """,
            countQuery = """
            select count(s) from Showtime s
            where (:cinemaId is null or s.room.cinema.id = :cinemaId)
              and (:movieId is null or s.movie.id = :movieId)
              and s.startTime >= :from and s.startTime < :to
            """)
    Page<Showtime> search(Long cinemaId, Long movieId, LocalDateTime from, LocalDateTime to, Pageable pageable);

    boolean existsByRoomId(Long roomId);

    boolean existsByMovieId(Long movieId);
}
