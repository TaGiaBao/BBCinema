package com.bbcinema.entity;

import com.bbcinema.entity.enums.SeatStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One seat in one showtime. Each row carries its own status and price, so a seat can be
 * sold exactly once per showtime. The unique key and the version column are the two guards
 * against double booking, on top of the row lock taken while holding seats.
 */
@Entity
@Table(name = "showtime_seats",
        uniqueConstraints = @UniqueConstraint(columnNames = {"showtime_id", "seat_id"}),
        indexes = @Index(name = "idx_ss_status_expiry", columnList = "status, hold_expires_at"))
@Getter
@Setter
@NoArgsConstructor
public class ShowtimeSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "showtime_id")
    private Showtime showtime;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seat_id")
    private Seat seat;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SeatStatus status = SeatStatus.AVAILABLE;

    @Column(nullable = false, precision = 12, scale = 0)
    private BigDecimal price;

    @Column(name = "hold_expires_at")
    private LocalDateTime holdExpiresAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id")
    private Booking booking;

    /** Counter sales: the ticket type of this seat (null = ordinary adult ticket). */
    @Enumerated(EnumType.STRING)
    @Column(name = "audience_type", length = 10)
    private com.bbcinema.entity.enums.AudienceType audienceType;

    /** How much the audience ticket took off {@link #price}; null when none. */
    @Column(name = "audience_discount", precision = 12, scale = 0)
    private BigDecimal audienceDiscount;

    @Version
    private Long version;

    /** Ticket type shown on the ticket: ADULT when none was chosen. */
    public com.bbcinema.entity.enums.AudienceType getTicketType() {
        return audienceType == null ? com.bbcinema.entity.enums.AudienceType.ADULT : audienceType;
    }

    /** What this seat costs after its audience discount. */
    public BigDecimal getChargedPrice() {
        return audienceDiscount == null ? price : price.subtract(audienceDiscount);
    }

    public void hold(Booking booking, LocalDateTime expiresAt) {
        this.status = SeatStatus.HELD;
        this.booking = booking;
        this.holdExpiresAt = expiresAt;
    }

    public void markBooked() {
        this.status = SeatStatus.BOOKED;
        this.holdExpiresAt = null;
    }

    public void release() {
        this.status = SeatStatus.AVAILABLE;
        this.booking = null;
        this.holdExpiresAt = null;
        this.audienceType = null;
        this.audienceDiscount = null;
    }
}
