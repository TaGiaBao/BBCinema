package com.bbcinema.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "rooms")
@Getter
@Setter
@NoArgsConstructor
public class Room {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cinema_id")
    private Cinema cinema;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "row_count", nullable = false)
    private int rowCount;

    @Column(name = "seats_per_row", nullable = false)
    private int seatsPerRow;

    @Column(nullable = false)
    private boolean active = true;

    @OneToMany(mappedBy = "room", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("rowLabel ASC, number ASC")
    private List<Seat> seats = new ArrayList<>();

    public void addSeat(Seat seat) {
        seat.setRoom(this);
        seats.add(seat);
    }

    public long getActiveSeatCount() {
        return seats.stream().filter(Seat::isActive).count();
    }
}
