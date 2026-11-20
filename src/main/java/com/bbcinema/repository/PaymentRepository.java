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

    /** Successful payments for counter sales made by one staff member in a time range (for the till summary). */
    @Query("select p from Payment p where p.status in (com.bbcinema.entity.enums.PaymentStatus.SUCCESS, "
            + "com.bbcinema.entity.enums.PaymentStatus.REFUNDED) "
            + "and p.booking.soldBy.id = :staffId and p.paidAt >= :from and p.paidAt < :to")
    List<Payment> findCounterTakings(@Param("staffId") Long staffId, @Param("from") LocalDateTime from,
                                     @Param("to") LocalDateTime to);

    /** Counter cash sales that one staff member refunded (handed the cash back) in a time range. */
    @Query("select p from Payment p where p.status = com.bbcinema.entity.enums.PaymentStatus.REFUNDED "
            + "and p.method = com.bbcinema.entity.enums.PaymentMethod.CASH "
            + "and p.refundedBy.id = :staffId and p.refundedAt >= :from and p.refundedAt < :to")
    List<Payment> findCashRefunds(@Param("staffId") Long staffId, @Param("from") LocalDateTime from,
                                  @Param("to") LocalDateTime to);

    /** When the oldest bank transfer still waiting for its money was started; null when none is waiting. */
    @Query("select min(p.createdAt) from Payment p where p.method = :method and p.status = com.bbcinema.entity.enums.PaymentStatus.PENDING "
            + "and p.booking.status = com.bbcinema.entity.enums.BookingStatus.PENDING")
    LocalDateTime oldestWaiting(@Param("method") PaymentMethod method);
}
