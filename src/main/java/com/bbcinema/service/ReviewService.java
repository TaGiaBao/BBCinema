package com.bbcinema.service;

import com.bbcinema.entity.Movie;
import com.bbcinema.entity.Review;
import com.bbcinema.entity.User;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.exception.NotFoundException;
import com.bbcinema.repository.BookingRepository;
import com.bbcinema.repository.MovieRepository;
import com.bbcinema.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Movie ratings. Only members who watched the movie (a paid ticket for a showtime that has started)
 * can rate it. The average and count are kept on the movie so lists can show them without extra queries.
 */
@Service
@RequiredArgsConstructor
public class ReviewService {

    public static final int MAX_COMMENT = 1000;

    private final ReviewRepository reviewRepository;
    private final MovieRepository movieRepository;
    private final BookingRepository bookingRepository;
    private final UserService userService;
    private final Clock clock;

    public boolean hasWatched(Long userId, Long movieId) {
        return userId != null && bookingRepository.countWatched(userId, movieId, LocalDateTime.now(clock)) > 0;
    }

    public Optional<Review> findOwn(Long movieId, Long userId) {
        return userId == null ? Optional.empty() : reviewRepository.findByMovieIdAndUserId(movieId, userId);
    }

    public List<Review> latest(Long movieId, int limit) {
        return reviewRepository.findVisible(movieId, PageRequest.of(0, limit));
    }

    /** Creates the member's review of the movie, or updates it if they already wrote one. */
    @Transactional
    public Review save(Long userId, Long movieId, int rating, String comment) {
        if (rating < 1 || rating > 5) {
            throw new BusinessException("Vui lòng chọn từ 1 đến 5 sao");
        }
        String text = comment == null ? null : comment.trim();
        if (text != null && text.length() > MAX_COMMENT) {
            throw new BusinessException("Nhận xét tối đa " + MAX_COMMENT + " ký tự");
        }
        Movie movie = movieRepository.findById(movieId).orElseThrow(() -> new NotFoundException("Không tìm thấy phim"));
        if (!hasWatched(userId, movieId)) {
            throw new BusinessException("Bạn có thể đánh giá sau khi đã xem phim này tại BBCinema");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Review review = reviewRepository.findByMovieIdAndUserId(movieId, userId).orElseGet(() -> {
            Review r = new Review();
            r.setMovie(movie);
            r.setUser(userService.getById(userId));
            r.setCreatedAt(now);
            return r;
        });
        review.setRating(rating);
        review.setComment(text == null || text.isEmpty() ? null : text);
        review.setUpdatedAt(now);
        reviewRepository.save(review);
        refreshStats(movie);
        return review;
    }

    public Page<Review> forAdmin(Pageable pageable) {
        return reviewRepository.findAllForAdmin(pageable);
    }

    @Transactional
    public void setHidden(Long reviewId, boolean hidden) {
        Review review = reviewRepository.findById(reviewId).orElseThrow(() -> new NotFoundException("Không tìm thấy đánh giá"));
        review.setHidden(hidden);
        reviewRepository.flush();
        refreshStats(review.getMovie());
    }

    void refreshStats(Movie movie) {
        reviewRepository.flush();
        Object[] row = reviewRepository.stats(movie.getId()).get(0);
        long count = ((Number) row[1]).longValue();
        movie.setRatingCount((int) count);
        movie.setRatingAverage(count == 0 ? null : Math.round(((Number) row[0]).doubleValue() * 10) / 10.0);
    }

    /** Used by the demo data: adds a review without the "has watched" check. */
    @Transactional
    public void seed(User user, Movie movie, int rating, String comment) {
        Review r = new Review();
        r.setUser(user);
        r.setMovie(movie);
        r.setRating(rating);
        r.setComment(comment);
        r.setCreatedAt(LocalDateTime.now(clock));
        r.setUpdatedAt(r.getCreatedAt());
        reviewRepository.save(r);
        refreshStats(movieRepository.findById(movie.getId()).orElseThrow());
    }
}
