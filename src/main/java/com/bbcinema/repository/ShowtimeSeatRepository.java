package com.bbcinema.repository;

import com.bbcinema.entity.ShowtimeSeat;
import com.bbcinema.entity.enums.SeatStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

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
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select ss from ShowtimeSeat ss join fetch ss.seat
            where ss.showtime.id = :showtimeId and ss.id in :ids
            """)
    List<ShowtimeSeat> findForUpdate(Long showtimeId, Collection<Long> ids);

    /**
     * Every seat of a showtime, row-locked (SELECT ... FOR UPDATE) in id order. Used to re-price seats
     * without racing a customer who is holding them: whoever locks first wins, the other one waits
     * and then sees the committed status and price.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select ss from ShowtimeSeat ss join fetch ss.seat
            where ss.showtime.id = :showtimeId
            order by ss.id
            """)
    List<ShowtimeSeat> findAllOfShowtimeForUpdate(Long showtimeId);

    /** The different prices of the free seats of one type in a showtime, leaving out the given seats. */
    @Query("""
            select distinct ss.price from ShowtimeSeat ss
            where ss.showtime.id = :showtimeId and ss.seat.type = :seatType
              and ss.status = com.bbcinema.entity.enums.SeatStatus.AVAILABLE and ss.id not in :excludedIds
            """)
    List<java.math.BigDecimal> findFreeSeatPrices(Long showtimeId, com.bbcinema.entity.enums.SeatType seatType,
                                                  Collection<Long> excludedIds);

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
