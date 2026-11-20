package com.bbcinema.repository;

import com.bbcinema.entity.CounterShift;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CounterShiftRepository extends JpaRepository<CounterShift, Long> {

    /** The staff member's shift that has not been closed yet (at most one). */
    @EntityGraph(attributePaths = {"staff", "cinema", "openedBy"})
    Optional<CounterShift> findFirstByStaffIdAndClosedAtIsNullOrderByOpenedAtDesc(Long staffId);

    @EntityGraph(attributePaths = {"staff", "cinema", "openedBy", "closedBy"})
    List<CounterShift> findTop100ByOrderByOpenedAtDesc();

    @EntityGraph(attributePaths = {"staff", "cinema", "openedBy", "closedBy"})
    Optional<CounterShift> findDetailedById(Long id);

    Optional<CounterShift> findFirstByOrderByOpenedAtDesc();
}
