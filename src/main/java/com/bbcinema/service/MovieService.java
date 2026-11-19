package com.bbcinema.service;

import com.bbcinema.dto.request.MovieForm;
import com.bbcinema.entity.Genre;
import com.bbcinema.entity.Movie;
import com.bbcinema.entity.enums.MovieStatus;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.exception.NotFoundException;
import com.bbcinema.repository.GenreRepository;
import com.bbcinema.repository.MovieRepository;
import com.bbcinema.repository.ShowtimeRepository;
import com.bbcinema.util.SlugUtil;
import com.bbcinema.util.TextUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MovieService {

    private final MovieRepository movieRepository;
    private final GenreRepository genreRepository;
    private final ShowtimeRepository showtimeRepository;
    private final FileStorageService fileStorageService;
    private final Clock clock;

    public Movie getById(Long id) {
        return movieRepository.findById(id).orElseThrow(() -> new NotFoundException("Không tìm thấy phim"));
    }

    public Movie getBySlug(String slug) {
        return movieRepository.findBySlug(slug).orElseThrow(() -> new NotFoundException("Không tìm thấy phim"));
    }

    public List<Movie> findByStatus(MovieStatus status) {
        return movieRepository.findByStatusOrderByReleaseDateDesc(status);
    }

    /** How many upcoming movies the "you may like" sections show. */
    public static final int FEATURED_COMING_SOON = 4;

    /**
     * Upcoming movies to suggest: the ones opening soonest first (movies without a release date
     * last), leaving out the movie the visitor is already looking at.
     */
    public List<Movie> featuredComingSoon(Long excludeMovieId) {
        return movieRepository.findByStatusOrderByReleaseDateDesc(MovieStatus.COMING_SOON).stream()
                .filter(m -> !m.getId().equals(excludeMovieId))
                .sorted(Comparator.comparing(Movie::getReleaseDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(FEATURED_COMING_SOON)
                .toList();
    }

    public List<Movie> findWithSpecialShowtimes(Long cinemaId) {
        if (cinemaId == null) {
            return List.of();
        }
        return movieRepository.findWithSpecialShowtimes(cinemaId, LocalDateTime.now(clock));
    }

    public Page<Movie> search(String keyword, MovieStatus status, Long genreId, Pageable pageable) {
        return movieRepository.search(TextUtil.blankToNull(keyword), status, genreId, pageable);
    }

    public List<Genre> findAllGenres() {
        return genreRepository.findAllByOrderByNameAsc();
    }

    @Transactional
    public Genre createGenre(String name) {
        String trimmed = TextUtil.blankToNull(name);
        if (trimmed == null) {
            throw new BusinessException("Vui lòng nhập tên thể loại");
        }
        if (genreRepository.findByNameIgnoreCase(trimmed).isPresent()) {
            throw new BusinessException("Thể loại \"" + trimmed + "\" đã tồn tại");
        }
        return genreRepository.save(new Genre(trimmed));
    }

    @Transactional
    public void deleteGenre(Long id) {
        Genre genre = genreRepository.findById(id).orElseThrow(() -> new NotFoundException("Không tìm thấy thể loại"));
        movieRepository.findAll().forEach(m -> m.getGenres().remove(genre));
        genreRepository.delete(genre);
    }

    @Transactional
    public Movie save(MovieForm form, MultipartFile poster) {
        Movie movie = form.getId() == null ? new Movie() : getById(form.getId());
        boolean titleChanged = movie.getTitle() == null || !movie.getTitle().equals(form.getTitle().trim());
        movie.setTitle(form.getTitle().trim());
        if (titleChanged) {
            movie.setSlug(uniqueSlug(movie.getTitle(), movie.getId()));
        }
        movie.setDirector(TextUtil.blankToNull(form.getDirector()));
        movie.setActors(TextUtil.blankToNull(form.getActors()));
        movie.setDescription(TextUtil.blankToNull(form.getDescription()));
        movie.setDurationMinutes(form.getDurationMinutes());
        movie.setAgeRating(form.getAgeRating());
        movie.setReleaseDate(form.getReleaseDate());
        movie.setLanguage(TextUtil.blankToNull(form.getLanguage()));
        movie.setTrailerUrl(TextUtil.blankToNull(form.getTrailerUrl()));
        movie.setStatus(form.getStatus());
        movie.setGenres(new LinkedHashSet<>(genreRepository.findAllById(form.getGenreIds())));
        String posterPath = fileStorageService.storeImage(poster, "posters");
        if (posterPath != null) {
            movie.setPosterPath(posterPath);
        }
        return movieRepository.save(movie);
    }

    @Transactional
    public void delete(Long id) {
        Movie movie = getById(id);
        if (showtimeRepository.existsByMovieId(id)) {
            throw new BusinessException("Phim đã có suất chiếu nên không thể xoá. Hãy chuyển trạng thái sang \"Ngừng chiếu\".");
        }
        movieRepository.delete(movie);
    }

    public MovieForm toForm(Movie movie) {
        MovieForm form = new MovieForm();
        form.setId(movie.getId());
        form.setTitle(movie.getTitle());
        form.setDirector(movie.getDirector());
        form.setActors(movie.getActors());
        form.setDescription(movie.getDescription());
        form.setDurationMinutes(movie.getDurationMinutes());
        form.setAgeRating(movie.getAgeRating());
        form.setReleaseDate(movie.getReleaseDate());
        form.setLanguage(movie.getLanguage());
        form.setTrailerUrl(movie.getTrailerUrl());
        form.setStatus(movie.getStatus());
        form.setGenreIds(movie.getGenres().stream().map(Genre::getId).toList());
        form.setPosterPath(movie.getPosterPath());
        return form;
    }

    private String uniqueSlug(String title, Long currentId) {
        String base = SlugUtil.toSlug(title);
        if (base.isEmpty()) {
            base = "phim";
        }
        String slug = base;
        int counter = 2;
        while (true) {
            var existing = movieRepository.findBySlug(slug);
            if (existing.isEmpty() || existing.get().getId().equals(currentId)) {
                return slug;
            }
            slug = base + "-" + counter++;
        }
    }
}
