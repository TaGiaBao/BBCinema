package com.bbcinema.repository;

import com.bbcinema.entity.StockRequest;
import com.bbcinema.entity.enums.StockRequestStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface StockRequestRepository extends JpaRepository<StockRequest, Long> {

    @EntityGraph(attributePaths = {"cinema", "requestedBy", "reviewedBy"})
    List<StockRequest> findTop100ByOrderByRequestedAtDesc();

    @EntityGraph(attributePaths = {"cinema", "requestedBy", "reviewedBy"})
    List<StockRequest> findTop100ByCinemaIdOrderByRequestedAtDesc(Long cinemaId);

    long countByStatus(StockRequestStatus status);

    /** Stock on hand of one cinema: the sum of approved goods-received notes, per item id. */
    @Query("select l.item.id, sum(l.quantity) from StockRequestLine l where l.request.cinema.id = :cinemaId "
            + "and l.request.status = com.bbcinema.entity.enums.StockRequestStatus.APPROVED group by l.item.id")
    List<Object[]> stockOf(@Param("cinemaId") Long cinemaId);
}
