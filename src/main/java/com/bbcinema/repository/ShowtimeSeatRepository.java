package com.bbcinema.repository;

import com.bbcinema.entity.ShowtimeSeat;
import com.bbcinema.entity.enums.SeatStatus;
import com.bbcinema.entity.enums.SeatType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface ShowtimeSeatRepository extends JpaRepository<ShowtimeSeat, Long> {

    @Query("""
            select ss from ShowtimeSeat ss join fetch ss.seat s
            where ss.showtime.id = :showtimeId
            order by s.rowLabel, s.number
            """)
    List<ShowtimeSeat> findSeatMap(Long showtimeId);

    /**
     * Loads the requested seats with a row lock (SELECT ... FOR UPDATE). A second buyer asking
     * for the same seats waits here until the first transaction commits, then sees the new status.
     * Rows are locked in id order, so two transactions locking overlapping seats (two holds, or a
     * hold and a holiday re-pricing) wait for each other instead of deadlocking.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select ss from ShowtimeSeat ss join fetch ss.seat
            where ss.showtime.id = :showtimeId and ss.id in :ids
            order by ss.id
            """)
    List<ShowtimeSeat> findForUpdate(Long showtimeId, Collection<Long> ids);

    /**
     * Ids of the seats of a showtime a customer can pick now: free ones, and held ones whose hold ran
     * out but has not been released yet (like {@code ShowtimeService.effectiveStatus}). No lock.
     */
    @Query("""
            select ss.id from ShowtimeSeat ss
            where ss.showtime.id = :showtimeId
              and (ss.status = com.bbcinema.entity.enums.SeatStatus.AVAILABLE
                   or (ss.status = com.bbcinema.entity.enums.SeatStatus.HELD and ss.holdExpiresAt < :now))
            order by ss.id
            """)
    List<Long> findFreeSeatIds(Long showtimeId, LocalDateTime now);

    /**
     * The different prices of the seats of one type in a showtime a customer can pick now (free, or
     * held past the hold time), leaving out the given seats.
     */
    @Query("""
            select distinct ss.price from ShowtimeSeat ss
            where ss.showtime.id = :showtimeId and ss.seat.type = :seatType and ss.id not in :excludedIds
              and (ss.status = com.bbcinema.entity.enums.SeatStatus.AVAILABLE
                   or (ss.status = com.bbcinema.entity.enums.SeatStatus.HELD and ss.holdExpiresAt < :now))
            """)
    List<BigDecimal> findFreeSeatPrices(Long showtimeId, SeatType seatType, Collection<Long> excludedIds, LocalDateTime now);

    long countByShowtimeIdAndStatus(Long showtimeId, SeatStatus status);

    long countByShowtimeId(Long showtimeId);

    /** Number of seats of each of these bookings, as rows of [booking id, seat count]. */
    @Query("""
            select ss.booking.id, count(ss) from ShowtimeSeat ss
            where ss.booking.id in :bookingIds
            group by ss.booking.id
            """)
    List<Object[]> countSeatsByBooking(Collection<Long> bookingIds);

    @Query("""
            select ss from ShowtimeSeat ss
            where ss.status = com.bbcinema.entity.enums.SeatStatus.HELD and ss.holdExpiresAt < :now
            """)
    List<ShowtimeSeat> findExpiredHolds(LocalDateTime now);

    /** Seats of a room that any showtime uses; tickets point to them, so they cannot be deleted. */
    @Query("select distinct ss.seat.id from ShowtimeSeat ss where ss.seat.room.id = :roomId")
    java.util.Set<Long> findSeatIdsUsedInRoom(Long roomId);

    /** Seats of a room that are held or sold for a showtime that has not ended yet. */
    @Query("""
            select distinct ss.seat.id from ShowtimeSeat ss
            where ss.seat.room.id = :roomId and ss.showtime.endTime > :now
              and ss.status in (com.bbcinema.entity.enums.SeatStatus.HELD, com.bbcinema.entity.enums.SeatStatus.BOOKED)
            """)
    java.util.Set<Long> findSeatIdsTakenInRoom(Long roomId, java.time.LocalDateTime now);
}
