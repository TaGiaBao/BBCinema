package com.bbcinema.repository;

import com.bbcinema.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByTransactionRef(String transactionRef);

    List<Payment> findByBookingIdOrderByCreatedAtDesc(Long bookingId);
}
