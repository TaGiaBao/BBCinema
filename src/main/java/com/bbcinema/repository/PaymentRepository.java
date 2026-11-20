package com.bbcinema.repository;

import com.bbcinema.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

import com.bbcinema.entity.enums.PaymentMethod;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByTransactionRef(String transactionRef);

    List<Payment> findByBookingIdOrderByCreatedAtDesc(Long bookingId);

    /** When the oldest bank transfer still waiting for its money was started; null when none is waiting. */
    @Query("select min(p.createdAt) from Payment p where p.method = :method and p.status = com.bbcinema.entity.enums.PaymentStatus.PENDING "
            + "and p.booking.status = com.bbcinema.entity.enums.BookingStatus.PENDING")
    LocalDateTime oldestWaiting(@Param("method") PaymentMethod method);
}
