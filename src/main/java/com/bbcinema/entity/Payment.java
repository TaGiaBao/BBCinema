package com.bbcinema.entity;

import com.bbcinema.entity.enums.PaymentMethod;
import com.bbcinema.entity.enums.PaymentStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id")
    private Booking booking;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(nullable = false, precision = 12, scale = 0)
    private BigDecimal amount;

    /** Reference sent to / received from the payment gateway. */
    @Column(name = "transaction_ref", length = 60)
    private String transactionRef;

    @Column(name = "gateway_transaction_no", length = 60)
    private String gatewayTransactionNo;

    /** Counter sales: cash handed over by the customer, or the amount on the POS card slip. */
    @Column(name = "amount_received", precision = 12, scale = 0)
    private BigDecimal amountReceived;

    /** Counter cash sales: change given back to the customer (amount received minus amount). */
    @Column(name = "change_amount", precision = 12, scale = 0)
    private BigDecimal changeAmount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;
}
