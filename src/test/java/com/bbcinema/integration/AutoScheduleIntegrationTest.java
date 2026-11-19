package com.bbcinema.integration;

import com.bbcinema.entity.Genre;
import com.bbcinema.entity.Movie;
import com.bbcinema.entity.Showtime;
import com.bbcinema.entity.enums.AgeRating;
import com.bbcinema.entity.enums.MovieStatus;
import com.bbcinema.entity.enums.Role;
import com.bbcinema.repository.GenreRepository;
import com.bbcinema.repository.ShowtimeRepository;
import com.bbcinema.service.AutoScheduleService;
import com.bbcinema.service.AutoScheduleService.Planned;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AutoScheduleIntegrationTest extends IntegrationTestBase {

    @Autowired AutoScheduleService autoScheduleService;
    @Autowired GenreRepository genreRepository;
    @Autowired ShowtimeRepository showtimeRepository;

    private Movie movie(String title, int minutes, AgeRating rating, String genre) {
        int n = unique();
        Genre g = genreRepository.findAll().stream().filter(x -> x.getName().equals(genre)).findFirst()
                .orElseGet(() -> genreRepository.save(new Genre(genre)));
        Movie m = new Movie();
        m.setTitle(title + " " + n);
        m.setSlug("auto-" + n);
        m.setDurationMinutes(minutes);
        m.setAgeRating(rating);
        m.setStatus(MovieStatus.NOW_SHOWING);
        m.getGenres().add(g);
        return movieRepository.save(m);
    }

    @Test
    void planFillsRoomsWithoutClashesAndFollowsTheTimeRules() {
        Showtime existing = showtimeTomorrow(); // 19:00 tomorrow in a new cinema with one room
        Movie horror = movie("Kinh dị", 100, AgeRating.T18, "Kinh dị");
        Movie cartoon = movie("Hoạt hình", 90, AgeRating.P, "Hoạt hình");
        LocalDate day = existing.getStartTime().toLocalDate();
        AutoScheduleService.Request request = new AutoScheduleService.Request(existing.getRoom().getCinema().getId(),
                day, day.plusDays(1), LocalTime.of(8, 0), LocalTime.of(22, 30), LocalTime.of(18, 0), LocalTime.of(22, 0),
                List.of(horror.getId(), cartoon.getId(), existing.getMovie().getId()));

        List<Planned> plan = autoScheduleService.plan(request);

        assertThat(plan).hasSizeGreaterThan(8);
        List<Planned> sorted = new ArrayList<>(plan);
        sorted.sort((a, b) -> a.start().compareTo(b.start()));
        for (int i = 1; i < sorted.size(); i++) {
            assertThat(sorted.get(i).start()).isAfterOrEqualTo(sorted.get(i - 1).end()); // one room: no overlap
        }
        assertThat(plan).allSatisfy(p -> {
            assertThat(p.start().toLocalTime()).isBetween(LocalTime.of(8, 0), LocalTime.of(22, 30));
            assertThat(p.start().getMinute() % 5).isZero();
            // The existing 19:00 showtime is kept and worked around.
            boolean clash = p.start().isBefore(existing.getEndTime()) && p.end().isAfter(existing.getStartTime());
            assertThat(clash).isFalse();
        });
        // 18+ horror is never in the morning; the cartoon gets more daytime shows than the horror film.
        assertThat(plan).filteredOn(p -> p.movie().getId().equals(horror.getId()))
                .allMatch(p -> !p.start().toLocalTime().isBefore(LocalTime.of(12, 0)));
        long cartoonDay = plan.stream().filter(p -> p.movie().getId().equals(cartoon.getId())
                && p.start().toLocalTime().isBefore(LocalTime.of(17, 0))).count();
        long horrorDay = plan.stream().filter(p -> p.movie().getId().equals(horror.getId())
                && p.start().toLocalTime().isBefore(LocalTime.of(17, 0))).count();
        assertThat(cartoonDay).isGreaterThan(horrorDay);
        // Every chosen movie gets at least one show (variety penalty).
        assertThat(plan.stream().map(p -> p.movie().getId()).distinct().count()).isEqualTo(3);
        assertThat(plan).anyMatch(Planned::golden);
    }

    @Test
    void adminPreviewsThenSavesTheProposal() throws Exception {
        Showtime existing = showtimeTomorrow();
        Long cinemaId = existing.getRoom().getCinema().getId();
        String day = existing.getStartTime().toLocalDate().plusDays(3).toString();
        Cookie admin = authCookie(account(Role.ADMIN));

        mvc.perform(get("/admin/showtimes/auto").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Tạo lịch đề xuất")));
        String[] params = {"cinemaId", cinemaId.toString(), "from", day, "to", day, "open", "09:00", "lastStart", "22:00",
                "goldenFrom", "18:00", "goldenTo", "22:00", "movieIds", existing.getMovie().getId().toString()};
        var preview = post("/admin/showtimes/auto").with(csrf()).cookie(admin);
        var save = post("/admin/showtimes/auto/save").with(csrf()).cookie(admin);
        for (int i = 0; i < params.length; i += 2) {
            preview.param(params[i], params[i + 1]);
            save.param(params[i], params[i + 1]);
        }
        String page = mvc.perform(preview).andExpect(status().isOk())
                .andExpect(content().string(containsString("Lịch đề xuất"))).andReturn().getResponse().getContentAsString();
        long proposed = page.split("class=\"auto-slot", -1).length - 2; // minus the legend
        assertThat(proposed).isPositive();

        mvc.perform(save).andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("success", containsString("Đã tạo " + proposed + " suất chiếu")));
        LocalDateTime from = LocalDate.parse(day).atStartOfDay();
        assertThat(showtimeRepository.findOverlapping(existing.getRoom().getId(), from, from.plusDays(1), null))
                .hasSize((int) proposed);
    }

    @Test
    void badRequestsAreExplained() throws Exception {
        Showtime existing = showtimeTomorrow();
        LocalDate day = existing.getStartTime().toLocalDate();
        Cookie admin = authCookie(account(Role.ADMIN));
        mvc.perform(post("/admin/showtimes/auto").with(csrf()).cookie(admin)
                        .param("cinemaId", existing.getRoom().getCinema().getId().toString())
                        .param("from", day.toString()).param("to", day.plusDays(20).toString())
                        .param("open", "08:00").param("lastStart", "22:00").param("goldenFrom", "18:00").param("goldenTo", "22:00")
                        .param("movieIds", existing.getMovie().getId().toString()))
                .andExpect(content().string(containsString("tối đa 14 ngày")));
    }
}
