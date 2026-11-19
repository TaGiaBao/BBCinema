package com.bbcinema.entity;

import com.bbcinema.entity.enums.AgeRating;
import com.bbcinema.entity.enums.MovieStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Entity
@Table(name = "movies")
@Getter
@Setter
@NoArgsConstructor
public class Movie {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(nullable = false, unique = true, length = 170)
    private String slug;

    @Column(length = 120)
    private String director;

    @Column(length = 255)
    private String actors;

    @Column(length = 2000)
    private String description;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "age_rating", nullable = false, length = 10)
    private AgeRating ageRating = AgeRating.P;

    @Column(name = "release_date")
    private LocalDate releaseDate;

    @Column(length = 60)
    private String language;

    @Column(name = "poster_path")
    private String posterPath;

    @Column(name = "trailer_url")
    private String trailerUrl;

    /** Average of the visible member ratings (1-5, one decimal) and how many there are. Kept by ReviewService. */
    @Column(name = "rating_average")
    private Double ratingAverage;

    @Column(name = "rating_count", nullable = false)
    private int ratingCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MovieStatus status = MovieStatus.COMING_SOON;

    @ManyToMany
    @JoinTable(name = "movie_genres",
            joinColumns = @JoinColumn(name = "movie_id"),
            inverseJoinColumns = @JoinColumn(name = "genre_id"))
    @OrderBy("name")
    private Set<Genre> genres = new LinkedHashSet<>();

    public String getGenreNames() {
        return genres.stream().map(Genre::getName).collect(Collectors.joining(", "));
    }
}
