package com.bbcinema.web.admin;

import com.bbcinema.dto.request.ShowtimeForm;
import com.bbcinema.entity.enums.MovieStatus;
import com.bbcinema.entity.enums.ScreenFormat;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.service.CinemaService;
import com.bbcinema.service.MovieService;
import com.bbcinema.service.RoomService;
import com.bbcinema.service.ShowtimeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Controller
@RequestMapping("/admin/showtimes")
@RequiredArgsConstructor
public class AdminShowtimeController {

    private final ShowtimeService showtimeService;
    private final MovieService movieService;
    private final RoomService roomService;
    private final CinemaService cinemaService;
    private final Clock clock;

    @ModelAttribute("menu")
    public String menu() {
        return "showtimes";
    }

    @GetMapping
    public String list(@RequestParam(required = false) Long cinemaId,
                       @RequestParam(required = false) Long movieId,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(defaultValue = "0") int page,
                       Model model) {
        LocalDate start = from == null ? LocalDate.now(clock) : from;
        LocalDate end = to == null ? start.plusDays(ShowtimeService.BOOKING_WINDOW_DAYS - 1) : to;
        model.addAttribute("page", showtimeService.search(cinemaId, movieId, start, end, PageRequest.of(Math.max(page, 0), 20)));
        model.addAttribute("cinemaId", cinemaId);
        model.addAttribute("movieId", movieId);
        model.addAttribute("from", start);
        model.addAttribute("to", end);
        model.addAttribute("allCinemas", cinemaService.findAll());
        model.addAttribute("movies", activeMovies());
        if (!model.containsAttribute("form")) {
            ShowtimeForm form = new ShowtimeForm();
            form.setFromDate(LocalDate.now(clock).plusDays(1));
            form.setTimes("09:30, 12:15, 15:00, 18:30, 21:15");
            model.addAttribute("form", form);
        }
        model.addAttribute("rooms", roomService.findAll());
        model.addAttribute("formats", ScreenFormat.values());
        model.addAttribute("languages", com.bbcinema.entity.enums.ShowLanguage.values());
        return "admin/showtimes/list";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") ShowtimeForm form, BindingResult binding,
                         RedirectAttributes redirect) {
        if (binding.hasErrors()) {
            redirect.addFlashAttribute("error", binding.getAllErrors().get(0).getDefaultMessage());
            redirect.addFlashAttribute("form", form);
            return "redirect:/admin/showtimes";
        }
        try {
            int created = showtimeService.createShowtimes(form);
            redirect.addFlashAttribute("success", "Đã tạo " + created + " suất chiếu");
        } catch (BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            redirect.addFlashAttribute("form", form);
        }
        return "redirect:/admin/showtimes";
    }

    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            showtimeService.cancel(id);
            redirect.addFlashAttribute("success", "Đã huỷ suất chiếu");
        } catch (BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/showtimes";
    }

    private List<com.bbcinema.entity.Movie> activeMovies() {
        List<com.bbcinema.entity.Movie> movies = new ArrayList<>(movieService.findByStatus(MovieStatus.NOW_SHOWING));
        movies.addAll(movieService.findByStatus(MovieStatus.COMING_SOON));
        return movies;
    }
}
