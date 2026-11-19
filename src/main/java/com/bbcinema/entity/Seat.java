package com.bbcinema.entity;

import com.bbcinema.entity.enums.SeatType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "seats", uniqueConstraints = @UniqueConstraint(columnNames = {"room_id", "row_label", "number"}))
@Getter
@Setter
@NoArgsConstructor
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id")
    private Room room;

    @Column(name = "row_label", nullable = false, length = 2)
    private String rowLabel;

    @Column(nullable = false)
    private int number;

    @Enumerated(EnumType.STRING)
    @Column(name = "seat_type", nullable = false, length = 20)
    private SeatType type = SeatType.STANDARD;

    /** An inactive seat is a gap in the layout (aisle or broken seat) and is never sold. */
    @Column(nullable = false)
    private boolean active = true;

    public Seat(String rowLabel, int number, SeatType type) {
        this.rowLabel = rowLabel;
        this.number = number;
        this.type = type;
    }

    public String getLabel() {
        return rowLabel + number;
    }
}
