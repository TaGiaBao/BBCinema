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

    /** Successful or refunded payments of these bookings (for the revenue export). */
    @Query("select p from Payment p where p.booking.id in :bookingIds and p.status in "
            + "(com.bbcinema.entity.enums.PaymentStatus.SUCCESS, com.bbcinema.entity.enums.PaymentStatus.REFUNDED)")
    List<Payment> findSettledByBookingIds(@Param("bookingIds") java.util.Collection<Long> bookingIds);

    /** Payments of the tickets sold in a till shift (refunded ones too: the money did come in). */
    @Query("select p from Payment p where p.booking.shift.id = :shiftId and p.status in "
            + "(com.bbcinema.entity.enums.PaymentStatus.SUCCESS, com.bbcinema.entity.enums.PaymentStatus.REFUNDED)")
    List<Payment> findShiftSales(@Param("shiftId") Long shiftId);

    /** Refunds paid back from a till shift. */
    @Query("select p from Payment p where p.refundShift.id = :shiftId "
            + "and p.status = com.bbcinema.entity.enums.PaymentStatus.REFUNDED")
    List<Payment> findShiftRefunds(@Param("shiftId") Long shiftId);

    /** When the oldest bank transfer still waiting for its money was started; null when none is waiting. */
    @Query("select min(p.createdAt) from Payment p where p.method = :method and p.status = com.bbcinema.entity.enums.PaymentStatus.PENDING "
            + "and p.booking.status = com.bbcinema.entity.enums.BookingStatus.PENDING")
    LocalDateTime oldestWaiting(@Param("method") PaymentMethod method);
}
