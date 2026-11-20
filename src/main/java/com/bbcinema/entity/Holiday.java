package com.bbcinema.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/** A public holiday: showtimes starting on this date are sold at the holiday prices. */
@Entity
@Table(name = "holidays", uniqueConstraints = @UniqueConstraint(name = "uk_holidays_date", columnNames = "holiday_date"))
@Getter
@Setter
@NoArgsConstructor
public class Holiday {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "holiday_date", nullable = false)
    private LocalDate date;

    @Column(nullable = false, length = 100)
    private String name;

    public Holiday(LocalDate date, String name) {
        this.date = date;
        this.name = name;
    }
}
