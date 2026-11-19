package com.bbcinema.repository;

import com.bbcinema.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    List<Seat> findByRoomIdOrderByRowLabelAscNumberAsc(Long roomId);

    List<Seat> findByRoomIdAndActiveTrue(Long roomId);
}
