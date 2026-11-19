package com.bbcinema.web;

import com.bbcinema.entity.Cinema;
import com.bbcinema.entity.Movie;
import com.bbcinema.entity.enums.MovieStatus;
import com.bbcinema.service.MovieService;
import com.bbcinema.service.ShowtimeService;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.security.AppUserPrincipal;
import com.bbcinema.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.LocalDate;

@Controller
@RequestMapping("/movies")
@RequiredArgsConstructor
public class MovieController {

    private final MovieService movieService;
    private final ShowtimeService showtimeService;
    private final ReviewService reviewService;
    private final Clock clock;

    @GetMapping
    public String list(@RequestParam(required = false) String q,
                       @RequestParam(required = false) MovieStatus status,
                       @RequestParam(required = false) Long genre,
                       @RequestParam(defaultValue = "0") int page,
                       Model model) {
        model.addAttribute("page", movieService.search(q, status, genre, PageRequest.of(Math.max(page, 0), 12)));
        model.addAttribute("genres", movieService.findAllGenres());
        model.addAttribute("q", q);
        model.addAttribute("status", status);
        model.addAttribute("genre", genre);
        model.addAttribute("statuses", MovieStatus.values());
        return "movies/list";
    }

    @GetMapping("/{slug}")
    public String detail(@PathVariable String slug,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                         @ModelAttribute("currentCinema") Cinema currentCinema,
                         @ModelAttribute("currentUser") AppUserPrincipal currentUser,
                         Model model) {
        Movie movie = movieService.getBySlug(slug);
        LocalDate today = LocalDate.now(clock);
        LocalDate selected = date == null || date.isBefore(today) ? today : date;
        model.addAttribute("movie", movie);
        model.addAttribute("today", today);
        model.addAttribute("dates", showtimeService.upcomingDates());
        model.addAttribute("selectedDate", selected);
        model.addAttribute("comingSoonPicks", movieService.featuredComingSoon(movie.getId()));
        Long userId = currentUser == null ? null : currentUser.getId();
        model.addAttribute("reviews", reviewService.latest(movie.getId(), 20));
        model.addAttribute("ownReview", reviewService.findOwn(movie.getId(), userId).orElse(null));
        model.addAttribute("canReview", reviewService.hasWatched(userId, movie.getId()));
        model.addAttribute("showtimes", currentCinema == null ? java.util.List.of()
                : showtimeService.findForMovie(movie.getId(), currentCinema.getId(), selected));
        return "movies/detail";
    }

    /** A member rates the movie (creates or updates their review). */
    @PostMapping("/{slug}/reviews")
    public String review(@PathVariable String slug, @RequestParam int rating,
                         @RequestParam(required = false) String comment,
                         @AuthenticationPrincipal AppUserPrincipal user, RedirectAttributes redirect) {
        Movie movie = movieService.getBySlug(slug);
        try {
            reviewService.save(user.getId(), movie.getId(), rating, comment);
            redirect.addFlashAttribute("success", "Cảm ơn bạn đã đánh giá phim " + movie.getTitle());
        } catch (BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/movies/" + slug + "#reviews";
    }
}
