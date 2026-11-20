package com.bbcinema.integration;

import com.bbcinema.dto.request.RoomForm;
import com.bbcinema.entity.Cinema;
import com.bbcinema.entity.Genre;
import com.bbcinema.entity.Movie;
import com.bbcinema.entity.Room;
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
import java.util.Map;
import java.util.stream.Collectors;

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
                "goldenFrom", "18:00", "goldenTo", "22:00", "movieIds", existing.getMovie().getId().toString(),
                "trailerMinutes", "10", "tier_" + existing.getMovie().getId(), "1"};
        var preview = post("/admin/showtimes/auto").with(csrf()).cookie(admin);
        var save = post("/admin/showtimes/auto/save").with(csrf()).cookie(admin);
        for (int i = 0; i < params.length; i += 2) {
            preview.param(params[i], params[i + 1]);
            save.param(params[i], params[i + 1]);
        }
        String page = mvc.perform(preview).andExpect(status().isOk())
                .andExpect(content().string(containsString("Lịch đề xuất")))
                .andExpect(content().string(containsString("Tỷ lệ suất chiếu theo phim"))).andReturn().getResponse().getContentAsString();
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

    private Room room(Cinema cinema, String name, int rows, int perRow) {
        RoomForm form = new RoomForm();
        form.setCinemaId(cinema.getId());
        form.setName(name);
        form.setRowCount(rows);
        form.setSeatsPerRow(perRow);
        form.setVipRows("F,G,H,I");
        return roomService.create(form);
    }

    private Movie released(String title, int minutes, AgeRating rating, String genre, int daysAgo) {
        Movie m = movie(title, minutes, rating, genre);
        m.setReleaseDate(LocalDate.now().plusDays(3).minusDays(daysAgo));
        return movieRepository.save(m);
    }

    @Test
    void cleaningGrowsWithTheRoomAndTiersFollowWeeksSinceRelease() {
        assertThat(AutoScheduleService.cleaningMinutes(90)).isEqualTo(15);
        assertThat(AutoScheduleService.cleaningMinutes(100)).isEqualTo(15);
        assertThat(AutoScheduleService.cleaningMinutes(120)).isEqualTo(20);
        assertThat(AutoScheduleService.cleaningMinutes(300)).isEqualTo(25);
        LocalDate day = LocalDate.now();
        Movie m = new Movie();
        m.setReleaseDate(day.minusDays(5));
        assertThat(AutoScheduleService.autoTier(m, day)).isEqualTo(1);
        m.setReleaseDate(day.minusDays(20));
        assertThat(AutoScheduleService.autoTier(m, day)).isEqualTo(2);
        m.setReleaseDate(day.minusDays(60));
        assertThat(AutoScheduleService.autoTier(m, day)).isEqualTo(3);
        m.setRatingCount(10);
        m.setRatingAverage(4.5); // a well-loved older movie moves up a tier
        assertThat(AutoScheduleService.autoTier(m, day)).isEqualTo(2);
    }

    @Test
    void planAnchorsPrimeTimeStaggersRoomsAndSharesShowsByTier() {
        Cinema cinema = new Cinema();
        cinema.setName("Rạp xếp lịch " + unique());
        cinema.setAddress("Hà Nội");
        cinema = cinemaRepository.save(cinema);
        Room big = room(cinema, "Phòng A", 10, 14);    // 140 seats: 20 min cleaning
        room(cinema, "Phòng B", 10, 12);               // 120 seats
        room(cinema, "Phòng C", 9, 10);                // 90 seats: 15 min cleaning
        Movie blockbuster = released("Bom tấn", 120, AgeRating.T13, "Hành động", 3);
        Movie second = released("Phim tuần 3", 105, AgeRating.P, "Hài", 18);
        Movie old = released("Phim cũ", 95, AgeRating.P, "Tâm lý", 50);
        Movie cartoon = released("Hoạt hình", 90, AgeRating.P, "Hoạt hình", 45);
        Movie horror = released("Kinh dị", 100, AgeRating.T18, "Kinh dị", 20);
        LocalDate day = LocalDate.now().plusDays(3);
        List<Long> ids = List.of(blockbuster.getId(), second.getId(), old.getId(), cartoon.getId(), horror.getId());
        AutoScheduleService.Request request = new AutoScheduleService.Request(cinema.getId(), day, day,
                LocalTime.of(9, 0), LocalTime.of(22, 30), LocalTime.of(18, 0), LocalTime.of(22, 0), ids, 10, Map.of());

        List<Planned> plan = autoScheduleService.plan(request);

        // Each room: the next show starts after trailers + movie + cleaning for that room size.
        Map<String, List<Planned>> byRoom = plan.stream().collect(Collectors.groupingBy(p -> p.room().getName()));
        assertThat(byRoom).hasSize(3);
        byRoom.values().forEach(list -> {
            for (int i = 1; i < list.size(); i++) {
                Planned prev = list.get(i - 1);
                int cleaning = AutoScheduleService.cleaningMinutes(prev.room().getSeats().size());
                assertThat(prev.end()).isEqualTo(prev.start().plusMinutes(10 + prev.movie().getDurationMinutes()));
                assertThat(list.get(i).start()).isAfterOrEqualTo(prev.end().plusMinutes(cleaning));
            }
        });
        // Prime-time anchor: the largest room starts its tier 1 movie at 19:00.
        assertThat(plan).anyMatch(p -> p.room().getId().equals(big.getId()) && p.start().equals(day.atTime(19, 0))
                && p.movie().getId().equals(blockbuster.getId()));
        // Lobby staggering: no two rooms start within 10 minutes of each other.
        for (Planned a : plan) {
            for (Planned b : plan) {
                if (!a.room().getId().equals(b.room().getId())) {
                    assertThat(Math.abs(java.time.Duration.between(a.start(), b.start()).toMinutes()))
                            .as("%s %s vs %s %s", a.room().getName(), a.start(), b.room().getName(), b.start())
                            .isGreaterThanOrEqualTo(10);
                }
            }
        }
        assertThat(plan).allSatisfy(p -> assertThat(p.start().getMinute() % 5).isZero());
        assertThat(plan).filteredOn(p -> p.movie().getId().equals(horror.getId()))
                .allMatch(p -> !p.start().toLocalTime().isBefore(LocalTime.NOON));
        // Shares: every movie is shown, the tier 1 movie most, and tier 1 owns more of prime time than tier 3.
        Map<Long, Long> shows = plan.stream().collect(Collectors.groupingBy(p -> p.movie().getId(), Collectors.counting()));
        assertThat(shows.keySet()).containsExactlyInAnyOrderElementsOf(ids);
        long top = shows.get(blockbuster.getId());
        assertThat(shows.values()).allMatch(n -> n <= top);
        assertThat(top).isGreaterThanOrEqualTo(Math.round(plan.size() * 0.3));
        long goldenTier1 = plan.stream().filter(p -> p.golden() && p.tier() == 1).count();
        long goldenTier3 = plan.stream().filter(p -> p.golden() && p.tier() == 3).count();
        assertThat(goldenTier1).isGreaterThan(goldenTier3);
        assertThat(AutoScheduleService.shares(plan)).hasSize(5);
    }

    @Test
    void adminCanSetATierByHand() {
        Showtime existing = showtimeTomorrow();
        Movie a = released("Hạng tay A", 100, AgeRating.P, "Hài", 60);
        Movie b = released("Hạng tay B", 100, AgeRating.P, "Hài", 60);
        LocalDate day = existing.getStartTime().toLocalDate().plusDays(2);
        AutoScheduleService.Request request = new AutoScheduleService.Request(existing.getRoom().getCinema().getId(),
                day, day, LocalTime.of(9, 0), LocalTime.of(22, 30), LocalTime.of(18, 0), LocalTime.of(22, 0),
                List.of(a.getId(), b.getId()), 10, Map.of(a.getId(), 1));
        List<Planned> plan = autoScheduleService.plan(request);
        long countA = plan.stream().filter(p -> p.movie().getId().equals(a.getId())).count();
        long countB = plan.stream().filter(p -> p.movie().getId().equals(b.getId())).count();
        assertThat(plan).filteredOn(p -> p.movie().getId().equals(a.getId())).allMatch(p -> p.tier() == 1);
        assertThat(countA).isGreaterThan(countB);
    }
}
