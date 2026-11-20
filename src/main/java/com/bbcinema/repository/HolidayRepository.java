package com.bbcinema.repository;

import com.bbcinema.entity.Holiday;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface HolidayRepository extends JpaRepository<Holiday, Long> {

    boolean existsByDate(LocalDate date);

    Optional<Holiday> findByDate(LocalDate date);

    List<Holiday> findAllByOrderByDateAsc();

    /** Holidays from {@code from} to {@code to}, both included. */
    List<Holiday> findByDateBetweenOrderByDateAsc(LocalDate from, LocalDate to);

    @Query("select h.date from Holiday h where h.date >= :from order by h.date")
    List<LocalDate> findDatesFrom(LocalDate from);
}
