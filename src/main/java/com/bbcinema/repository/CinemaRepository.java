package com.bbcinema.repository;

import com.bbcinema.entity.Cinema;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CinemaRepository extends JpaRepository<Cinema, Long> {

    List<Cinema> findByActiveTrueOrderByNameAsc();

    List<Cinema> findAllByOrderByNameAsc();
}
