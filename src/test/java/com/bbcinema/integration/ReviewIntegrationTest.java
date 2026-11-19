package com.bbcinema.integration;

import com.bbcinema.entity.Booking;
import com.bbcinema.entity.Movie;
import com.bbcinema.entity.Showtime;
import com.bbcinema.entity.User;
import com.bbcinema.entity.enums.AgeRating;
import com.bbcinema.entity.enums.Role;
import com.bbcinema.repository.ReviewRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReviewIntegrationTest extends IntegrationTestBase {

    @Autowired ReviewRepository reviewRepository;
    @Autowired com.bbcinema.repository.ShowtimeRepository showtimeRepository;

    /** A member with a paid ticket for a showtime that started an hour ago. */
    private User viewerOf(Showtime started) {
        User member = member(25);
        Booking b = bookingService.holdSeats(member.getId(), started.getId(), freeSeats(started.getId(), 1));
        bookingService.payWithMock(b.getCode(), member.getId());
        return member;
    }

    private Showtime startedShowtime() {
        // Created in the future (booking rules), then moved back so it has "already started".
        Showtime st = showtime(AgeRating.P, LocalDateTime.now().plusDays(1).withSecond(0).withNano(0));
        return st;
    }

    @Test
    void onlyMembersWhoWatchedCanRateAndTheAverageIsShown() throws Exception {
        Showtime st = startedShowtime();
        User watcher = viewerOf(st);
        User other = member(30);
        // The showtime has now started.
        st.setStartTime(LocalDateTime.now().minusHours(1));
        st.setEndTime(LocalDateTime.now().plusHours(1));
        showtimeRepositoryFlush(st);
        String slug = st.getMovie().getSlug();

        mvc.perform(get("/movies/" + slug).cookie(authCookie(other)))
                .andExpect(content().string(containsString("sau khi đã xem phim")));
        mvc.perform(post("/movies/" + slug + "/reviews").with(csrf()).cookie(authCookie(other)).param("rating", "5"))
                .andExpect(flash().attribute("error", containsString("sau khi đã xem phim")));

        mvc.perform(get("/movies/" + slug).cookie(authCookie(watcher)))
                .andExpect(content().string(containsString("Gửi đánh giá")));
        mvc.perform(post("/movies/" + slug + "/reviews").with(csrf()).cookie(authCookie(watcher))
                        .param("rating", "4").param("comment", "Hay lắm"))
                .andExpect(flash().attribute("success", containsString("Cảm ơn")));
        // Rating again updates the same review.
        mvc.perform(post("/movies/" + slug + "/reviews").with(csrf()).cookie(authCookie(watcher))
                        .param("rating", "2").param("comment", "Xem lại thấy chán"))
                .andExpect(flash().attribute("success", containsString("Cảm ơn")));

        Movie movie = movieRepository.findById(st.getMovie().getId()).orElseThrow();
        assertThat(movie.getRatingCount()).isEqualTo(1);
        assertThat(movie.getRatingAverage()).isEqualTo(2.0);
        mvc.perform(get("/movies/" + slug))
                .andExpect(content().string(containsString("Xem lại thấy chán")))
                .andExpect(content().string(containsString("1 đánh giá")));
        mvc.perform(get("/movies"))
                .andExpect(content().string(containsString("rating-badge")));
    }

    @Test
    void anonymousVisitorsMustLogInAndInvalidRatingsAreRefused() throws Exception {
        Showtime st = startedShowtime();
        String slug = st.getMovie().getSlug();
        mvc.perform(get("/movies/" + slug)).andExpect(content().string(containsString("Đăng nhập để đánh giá")));
        mvc.perform(post("/movies/" + slug + "/reviews").with(csrf()).param("rating", "5"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/login?redirect=*"));
        User watcher = viewerOf(st);
        mvc.perform(post("/movies/" + slug + "/reviews").with(csrf()).cookie(authCookie(watcher)).param("rating", "9"))
                .andExpect(flash().attribute("error", containsString("1 đến 5 sao")));
    }

    @Test
    void adminHidesReviewAndItLeavesTheAverage() throws Exception {
        Showtime st = startedShowtime();
        User watcher = viewerOf(st);
        st.setStartTime(LocalDateTime.now().minusHours(1));
        showtimeRepositoryFlush(st);
        Cookie member = authCookie(watcher);
        mvc.perform(post("/movies/" + st.getMovie().getSlug() + "/reviews").with(csrf()).cookie(member)
                .param("rating", "1").param("comment", "Spam quảng cáo"));
        Long reviewId = reviewRepository.findByMovieIdAndUserId(st.getMovie().getId(), watcher.getId()).orElseThrow().getId();

        Cookie admin = authCookie(account(Role.ADMIN));
        mvc.perform(get("/admin/reviews").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Spam quảng cáo")));
        mvc.perform(post("/admin/reviews/" + reviewId + "/hidden").with(csrf()).cookie(admin).param("hidden", "true"))
                .andExpect(flash().attribute("success", "Đã ẩn đánh giá"));

        Movie movie = movieRepository.findById(st.getMovie().getId()).orElseThrow();
        assertThat(movie.getRatingCount()).isZero();
        assertThat(movie.getRatingAverage()).isNull();
        mvc.perform(get("/movies/" + st.getMovie().getSlug()))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Spam quảng cáo"))));
        mvc.perform(get("/admin/reviews").cookie(authCookie(account(Role.STAFF)))).andExpect(status().isForbidden());
    }

    private void showtimeRepositoryFlush(Showtime st) {
        showtimeRepository.save(st);
    }
}
