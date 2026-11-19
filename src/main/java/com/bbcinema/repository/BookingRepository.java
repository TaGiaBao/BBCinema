package com.bbcinema.repository;

import com.bbcinema.entity.Booking;
import com.bbcinema.entity.enums.BookingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    @Query("""
            select b from Booking b
            join fetch b.showtime s join fetch s.movie join fetch s.room r join fetch r.cinema
            left join fetch b.user
            where b.code = :code
            """)
    Optional<Booking> findDetailedByCode(String code);

    boolean existsByCode(String code);

    @Query(value = """
            select b from Booking b
            join fetch b.showtime s join fetch s.movie join fetch s.room r join fetch r.cinema
            where b.user.id = :userId and b.status <> com.bbcinema.entity.enums.BookingStatus.EXPIRED
            order by b.createdAt desc
            """,
            countQuery = """
            select count(b) from Booking b
            where b.user.id = :userId and b.status <> com.bbcinema.entity.enums.BookingStatus.EXPIRED
            """)
    Page<Booking> findHistory(Long userId, Pageable pageable);

    List<Booking> findByStatusAndExpiresAtBefore(BookingStatus status, LocalDateTime time);

    @Query(value = """
            select b from Booking b
            join fetch b.showtime s join fetch s.movie join fetch s.room r join fetch r.cinema
            left join fetch b.user
            where (:status is null or b.status = :status)
              and (:keyword is null or lower(b.code) like lower(concat('%', :keyword, '%'))
                   or lower(s.movie.title) like lower(concat('%', :keyword, '%')))
            order by b.createdAt desc
            """,
            countQuery = """
            select count(b) from Booking b
            where (:status is null or b.status = :status)
              and (:keyword is null or lower(b.code) like lower(concat('%', :keyword, '%'))
                   or lower(b.showtime.movie.title) like lower(concat('%', :keyword, '%')))
            """)
    Page<Booking> search(BookingStatus status, String keyword, Pageable pageable);

    @Query("""
            select coalesce(sum(b.totalAmount), 0) from Booking b
            where b.status = com.bbcinema.entity.enums.BookingStatus.PAID
              and b.paidAt >= :from and b.paidAt < :to
            """)
    BigDecimal sumRevenue(LocalDateTime from, LocalDateTime to);

    @Query("""
            select count(b) from Booking b
            where b.status = com.bbcinema.entity.enums.BookingStatus.PAID
              and b.paidAt >= :from and b.paidAt < :to
            """)
    long countPaid(LocalDateTime from, LocalDateTime to);

    /** Paid bookings in a time range, used to build daily and per-movie revenue reports. */
    @Query("""
            select b from Booking b join fetch b.showtime s join fetch s.movie
            where b.status = com.bbcinema.entity.enums.BookingStatus.PAID
              and b.paidAt >= :from and b.paidAt < :to
            """)
    List<Booking> findPaidBetween(LocalDateTime from, LocalDateTime to);

    /** Paid bookings of this member for showtimes of the movie that have already started. */
    @Query("""
            select count(b) from Booking b
            where b.user.id = :userId and b.showtime.movie.id = :movieId
              and b.status = com.bbcinema.entity.enums.BookingStatus.PAID and b.showtime.startTime <= :now
            """)
    long countWatched(Long userId, Long movieId, java.time.LocalDateTime now);

    /** Total paid by the member for bookings paid since the given time (used for membership tiers). */
    @Query("""
            select sum(b.totalAmount) from Booking b
            where b.user.id = :userId and b.status = com.bbcinema.entity.enums.BookingStatus.PAID and b.paidAt >= :since
            """)
    java.math.BigDecimal sumPaidByUserSince(Long userId, java.time.LocalDateTime since);
}
