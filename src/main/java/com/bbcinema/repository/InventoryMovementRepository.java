package com.bbcinema.repository;

import com.bbcinema.entity.InventoryMovement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface InventoryMovementRepository extends JpaRepository<InventoryMovement, Long> {

    /** Net movement per item id at one cinema (negative = used by sales). */
    @Query("select m.item.id, sum(m.quantity) from InventoryMovement m where m.cinema.id = :cinemaId group by m.item.id")
    List<Object[]> totalsOf(@Param("cinemaId") Long cinemaId);

    List<InventoryMovement> findByBookingId(Long bookingId);
}
