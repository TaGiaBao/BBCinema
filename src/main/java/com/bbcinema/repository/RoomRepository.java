package com.bbcinema.repository;

import com.bbcinema.entity.Room;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface RoomRepository extends JpaRepository<Room, Long> {

    @Query("select r from Room r join fetch r.cinema order by r.cinema.name, r.name")
    List<Room> findAllWithCinema();

    List<Room> findByCinemaIdAndActiveTrueOrderByNameAsc(Long cinemaId);
}
