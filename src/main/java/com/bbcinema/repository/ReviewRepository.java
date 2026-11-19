package com.bbcinema.repository;

import com.bbcinema.entity.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    Optional<Review> findByMovieIdAndUserId(Long movieId, Long userId);

    @Query("""
            select r from Review r join fetch r.user
            where r.movie.id = :movieId and r.hidden = false
            order by r.updatedAt desc
            """)
    List<Review> findVisible(Long movieId, Pageable pageable);

    /** {average, count} of the visible ratings of a movie. */
    @Query("select avg(r.rating), count(r) from Review r where r.movie.id = :movieId and r.hidden = false")
    List<Object[]> stats(Long movieId);

    @Query(value = "select r from Review r join fetch r.user join fetch r.movie order by r.createdAt desc",
            countQuery = "select count(r) from Review r")
    Page<Review> findAllForAdmin(Pageable pageable);
}
