package com.bbcinema.entity;

import com.bbcinema.entity.enums.ScreenFormat;
import com.bbcinema.entity.enums.ShowLanguage;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "showtimes", indexes = @Index(name = "idx_showtime_start", columnList = "start_time"))
@Getter
@Setter
@NoArgsConstructor
public class Showtime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "movie_id")
    private Movie movie;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id")
    private Room room;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    /** Movie end plus cleaning time; used to stop two showtimes overlapping in one room. */
    @Column(name = "end_time", nullable = false)
    private LocalDateTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ScreenFormat format = ScreenFormat.TWO_D;

    /** Special screenings (early premieres, midnight shows) are listed in their own tab. */
    /** Subtitled, dubbed or Vietnamese. Null for showtimes made before this existed. */
    @Enumerated(EnumType.STRING)
    @Column(length = 12)
    private ShowLanguage language;

    @Column(nullable = false)
    private boolean special = false;

    @Column(nullable = false)
    private boolean cancelled = false;

    public ShowLanguage getLanguage() {
        if (language != null) {
            return language;
        }
        return ShowLanguage.defaultFor(movie == null ? null : movie.getLanguage());
    }

    /** What customers see next to the time, e.g. "2D Phụ đề". */
    public String getVersionLabel() {
        return format.getLabel() + " " + getLanguage().getLabel();
    }
}
