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

    long countByShowtimeIdAndStatus(Long showtimeId, SeatStatus status);

    long countByShowtimeId(Long showtimeId);

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
