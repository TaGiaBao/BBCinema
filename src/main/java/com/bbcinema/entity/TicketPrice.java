package com.bbcinema.entity;

import com.bbcinema.entity.enums.DayType;
import com.bbcinema.entity.enums.ScreenFormat;
import com.bbcinema.entity.enums.SeatType;
import com.bbcinema.entity.enums.TimeSlot;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "ticket_prices",
        uniqueConstraints = @UniqueConstraint(columnNames = {"seat_type", "format", "day_type", "time_slot"}))
@Getter
@Setter
@NoArgsConstructor
public class TicketPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "seat_type", nullable = false, length = 20)
    private SeatType seatType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ScreenFormat format;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_type", nullable = false, length = 10)
    private DayType dayType;

    @Enumerated(EnumType.STRING)
    @Column(name = "time_slot", nullable = false, length = 10)
    private TimeSlot timeSlot;

    @Column(nullable = false, precision = 12, scale = 0)
    private BigDecimal price;

    public TicketPrice(SeatType seatType, ScreenFormat format, DayType dayType, TimeSlot timeSlot, BigDecimal price) {
        this.seatType = seatType;
        this.format = format;
        this.dayType = dayType;
        this.timeSlot = timeSlot;
        this.price = price;
    }
}
