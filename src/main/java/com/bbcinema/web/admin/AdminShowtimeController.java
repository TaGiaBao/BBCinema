package com.bbcinema.web.admin;

import com.bbcinema.audit.Audited;
import com.bbcinema.dto.request.ShowtimeForm;
import com.bbcinema.entity.enums.MovieStatus;
import com.bbcinema.entity.enums.ScreenFormat;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.service.CinemaService;
import com.bbcinema.service.MovieService;
import com.bbcinema.service.RoomService;
import com.bbcinema.service.AutoScheduleService;
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
import java.util.Map;

@Controller
@RequestMapping("/admin/showtimes")
@RequiredArgsConstructor
public class AdminShowtimeController {

    private final ShowtimeService showtimeService;
    private final MovieService movieService;
    private final RoomService roomService;
    private final CinemaService cinemaService;
    private final Clock clock;
    private final AutoScheduleService autoScheduleService;

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

    @Audited("Tạo suất chiếu")
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

    @Audited("Huỷ suất chiếu")
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

    // ---------- automatic schedule

    @GetMapping("/auto")
    public String autoPage(Model model) {
        LocalDate tomorrow = LocalDate.now(clock).plusDays(1);
        List<Long> nowShowing = activeMovies().stream()
                .filter(m -> m.getStatus() == com.bbcinema.entity.enums.MovieStatus.NOW_SHOWING)
                .map(com.bbcinema.entity.Movie::getId).toList();
        Long firstCinema = cinemaService.findActive().stream().findFirst().map(com.bbcinema.entity.Cinema::getId).orElse(null);
        return autoForm(model, new AutoScheduleService.Request(firstCinema, tomorrow, tomorrow.plusDays(2),
                java.time.LocalTime.of(8, 0), java.time.LocalTime.of(22, 30),
                java.time.LocalTime.of(18, 0), java.time.LocalTime.of(22, 0), nowShowing), null);
    }

    /** Works out a proposal and shows it; nothing is saved yet. */
    @PostMapping("/auto")
    public String autoPreview(@ModelAttribute AutoScheduleForm form, @RequestParam Map<String, String> params, Model model) {
        AutoScheduleService.Request request = form.toRequest(params);
        try {
            return autoForm(model, request, autoScheduleService.plan(request));
        } catch (com.bbcinema.exception.BusinessException e) {
            model.addAttribute("error", e.getMessage());
            return autoForm(model, request, null);
        }
    }

    @Audited("Xếp lịch tự động")
    @PostMapping("/auto/save")
    public String autoSave(@ModelAttribute AutoScheduleForm form, @RequestParam Map<String, String> params,
                           RedirectAttributes redirect) {
        try {
            int created = autoScheduleService.save(form.toRequest(params));
            redirect.addFlashAttribute("success", "Đã tạo " + created + " suất chiếu theo lịch đề xuất");
        } catch (com.bbcinema.exception.BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/showtimes?cinemaId=" + form.getCinemaId();
    }

    private String autoForm(Model model, AutoScheduleService.Request request, List<AutoScheduleService.Planned> plan) {
        model.addAttribute("req", request);
        model.addAttribute("allCinemas", cinemaService.findActive());
        List<com.bbcinema.entity.Movie> movies = activeMovies().stream()
                .filter(m -> m.getStatus() != com.bbcinema.entity.enums.MovieStatus.ENDED).toList();
        model.addAttribute("movies", movies);
        LocalDate day = request.from() == null ? LocalDate.now(clock) : request.from();
        model.addAttribute("autoTiers", movies.stream().collect(java.util.stream.Collectors.toMap(
                com.bbcinema.entity.Movie::getId, m -> AutoScheduleService.autoTier(m, day))));
        if (plan != null) {
            model.addAttribute("plan", plan);
            model.addAttribute("shares", AutoScheduleService.shares(plan));
            model.addAttribute("days", plan.stream().map(p -> p.start().toLocalDate()).distinct().sorted().toList());
            model.addAttribute("roomNames", plan.stream().map(p -> p.room().getName()).distinct().sorted().toList());
        }
        return "admin/showtimes/auto";
    }

    /** Form fields of the automatic schedule page. */
    @lombok.Data
    public static class AutoScheduleForm {
        private Long cinemaId;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate from;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate to;
        @DateTimeFormat(pattern = "HH:mm")
        private java.time.LocalTime open;
        @DateTimeFormat(pattern = "HH:mm")
        private java.time.LocalTime lastStart;
        @DateTimeFormat(pattern = "HH:mm")
        private java.time.LocalTime goldenFrom;
        @DateTimeFormat(pattern = "HH:mm")
        private java.time.LocalTime goldenTo;
        private List<Long> movieIds = new java.util.ArrayList<>();
        private Integer trailerMinutes;

        /** Tiers the admin chose come as tier_{movieId} = 1..3; "auto" or anything else lets the system decide. */
        AutoScheduleService.Request toRequest(Map<String, String> params) {
            Map<Long, Integer> tiers = new java.util.HashMap<>();
            params.forEach((key, value) -> {
                if (key.startsWith("tier_") && value != null && value.matches("[123]")) {
                    try {
                        tiers.put(Long.parseLong(key.substring(5)), Integer.parseInt(value));
                    } catch (NumberFormatException ignored) {
                        // not a movie id
                    }
                }
            });
            return new AutoScheduleService.Request(cinemaId, from, to, open, lastStart, goldenFrom, goldenTo,
                    movieIds == null ? List.of() : movieIds,
                    trailerMinutes == null ? AutoScheduleService.DEFAULT_TRAILER_MINUTES : trailerMinutes, tiers);
        }
    }
}
