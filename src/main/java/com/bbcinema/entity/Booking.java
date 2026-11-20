package com.bbcinema.entity;

import com.bbcinema.entity.enums.BookingStatus;
import com.bbcinema.entity.enums.SalesChannel;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Entity
@Table(name = "bookings", indexes = {
        @Index(name = "idx_booking_status_expiry", columnList = "status, expires_at"),
        @Index(name = "idx_booking_user", columnList = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Short public code printed on the ticket and encoded in the QR code. */
    @Column(nullable = false, unique = true, length = 12)
    private String code;

    /** Member who booked online. Empty for walk-in counter sales. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "showtime_id")
    private Showtime showtime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BookingStatus status = BookingStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SalesChannel channel = SalesChannel.ONLINE;

    @Column(name = "customer_name", length = 100)
    private String customerName;

    @Column(name = "customer_phone", length = 20)
    private String customerPhone;

    @Column(name = "customer_email", length = 120)
    private String customerEmail;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sold_by_id")
    private User soldBy;

    /** Counter sales: the till shift the ticket was sold in. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shift_id")
    private CounterShift shift;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "promotion_id")
    private Promotion promotion;

    @Column(name = "ticket_amount", nullable = false, precision = 12, scale = 0)
    private BigDecimal ticketAmount = BigDecimal.ZERO;

    @Column(name = "combo_amount", nullable = false, precision = 12, scale = 0)
    private BigDecimal comboAmount = BigDecimal.ZERO;

    /** Membership tier of the member when the order was made, and the discount it gave. */
    @Enumerated(EnumType.STRING)
    @Column(name = "member_tier", length = 10)
    private com.bbcinema.entity.enums.MemberTier memberTier;

    @Column(name = "tier_discount", nullable = false, precision = 12, scale = 0)
    private BigDecimal tierDiscount = BigDecimal.ZERO;

    @Column(name = "promotion_discount", nullable = false, precision = 12, scale = 0)
    private BigDecimal promotionDiscount = BigDecimal.ZERO;

    @Column(name = "points_used", nullable = false)
    private int pointsUsed = 0;

    @Column(name = "points_earned", nullable = false)
    private int pointsEarned = 0;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 0)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** Deadline for paying a PENDING booking; after it the seats are released. */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "checked_in_at")
    private LocalDateTime checkedInAt;

    /** How many times staff printed a paper ticket for this booking at the cinema. */
    @org.hibernate.annotations.ColumnDefault("0")
    @Column(name = "print_count", nullable = false)
    private int printCount = 0;

    @Column(name = "last_printed_at")
    private LocalDateTime lastPrintedAt;

    @OneToMany(mappedBy = "booking")
    private List<ShowtimeSeat> seats = new ArrayList<>();

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BookingCombo> combos = new ArrayList<>();

    @Version
    private Long version;

    public BigDecimal getPointsDiscount() {
        return BigDecimal.valueOf(pointsUsed * 1000L);
    }

    public BigDecimal getDiscountAmount() {
        return tierDiscount.add(promotionDiscount).add(getPointsDiscount());
    }

    public String getSeatLabels() {
        return seats.stream()
                .map(ShowtimeSeat::getSeat)
                .sorted(Comparator.comparing(Seat::getRowLabel).thenComparingInt(Seat::getNumber))
                .map(Seat::getLabel)
                .collect(Collectors.joining(", "));
    }

    /** Hold deadline as epoch milliseconds, so the browser countdown does not depend on its own time zone. */
    public Long getExpiresAtEpochMillis() {
        return expiresAt == null ? null : expiresAt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    public boolean isPending() {
        return status == BookingStatus.PENDING;
    }

    public boolean isPaid() {
        return status == BookingStatus.PAID;
    }

    /** Name shown on the ticket: member name for online bookings, typed name for counter sales. */
    public String getDisplayName() {
        if (user != null) {
            return user.getFullName();
        }
        return customerName != null && !customerName.isBlank() ? customerName : "Khách lẻ";
    }
}
