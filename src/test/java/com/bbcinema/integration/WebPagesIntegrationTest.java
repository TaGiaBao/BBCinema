package com.bbcinema.integration;

import com.bbcinema.dto.request.CounterSaleForm;
import com.bbcinema.entity.Booking;
import com.bbcinema.entity.Cinema;
import com.bbcinema.entity.Movie;
import com.bbcinema.entity.Showtime;
import com.bbcinema.entity.User;
import com.bbcinema.entity.enums.AgeRating;
import com.bbcinema.entity.enums.MovieStatus;
import com.bbcinema.entity.enums.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Renders the Thymeleaf pages with real data, so template mistakes fail the build. */
class WebPagesIntegrationTest extends IntegrationTestBase {

    @Test
    void customerPagesRender() throws Exception {
        Showtime st = showtimeTomorrow();
        User member = member(25);
        Cookie auth = authCookie(member);
        Cookie cinema = new Cookie("BB_CINEMA", st.getRoom().getCinema().getId().toString());

        mvc.perform(get("/movies/" + st.getMovie().getSlug()).cookie(cinema)
                        .param("date", st.getStartTime().toLocalDate().toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/booking/showtime/" + st.getId())));
        mvc.perform(get("/schedule").cookie(cinema).param("date", st.getStartTime().toLocalDate().toString()))
                .andExpect(content().string(containsString(st.getMovie().getTitle())));
        mvc.perform(get("/booking/showtime/" + st.getId()).cookie(auth)).andExpect(status().isOk())
                .andExpect(content().string(containsString("MÀN HÌNH")));

        Booking booking = bookingService.holdSeats(member.getId(), st.getId(), freeSeats(st.getId(), 1));
        mvc.perform(get("/booking/" + booking.getCode() + "/combos").cookie(auth)).andExpect(status().isOk());
        mvc.perform(get("/booking/" + booking.getCode() + "/checkout").cookie(auth)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Phương thức thanh toán")));
        mvc.perform(post("/booking/" + booking.getCode() + "/pay").with(csrf()).cookie(auth).param("method", "MOCK"))
                .andExpect(redirectedUrl("/booking/" + booking.getCode()));
        mvc.perform(get("/booking/" + booking.getCode()).cookie(auth)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Đã thanh toán")));
        mvc.perform(get("/booking/" + booking.getCode() + "/qr.png").cookie(auth))
                .andExpect(content().contentTypeCompatibleWith("image/png"));
        for (String path : new String[]{"/member", "/member/bookings", "/member/points"}) {
            mvc.perform(get(path).cookie(auth)).andExpect(status().isOk());
        }
    }

    @Test
    void holdingTakenSeatShowsMessageOnSeatPage() throws Exception {
        Showtime st = showtimeTomorrow();
        Long seat = freeSeats(st.getId(), 1).get(0);
        bookingService.holdSeats(member(30).getId(), st.getId(), java.util.List.of(seat));
        mvc.perform(post("/booking/showtime/" + st.getId()).with(csrf()).cookie(authCookie(member(30)))
                        .param("seatIds", seat.toString()))
                .andExpect(redirectedUrl("/booking/showtime/" + st.getId()))
                .andExpect(flash().attribute("error", containsString("vừa có người chọn")));
    }

    @Test
    void adminPagesRender() throws Exception {
        Showtime st = showtimeTomorrow();
        Cookie admin = authCookie(account(Role.ADMIN));
        String[] pages = {"/admin", "/admin/movies", "/admin/movies/new", "/admin/movies/" + st.getMovie().getId() + "/edit",
                "/admin/cinemas", "/admin/cinemas/rooms/" + st.getRoom().getId(), "/admin/showtimes", "/admin/prices",
                "/admin/combos", "/admin/promotions", "/admin/posts", "/admin/posts/new", "/admin/banners", "/admin/users",
                "/admin/bookings", "/staff", "/staff/checkin", "/staff/sell/" + st.getId()};
        for (String page : pages) {
            mvc.perform(get(page).cookie(admin)).andExpect(status().isOk());
        }
    }

    @Test
    void adminCreatesMovieWithPoster() throws Exception {
        Cookie admin = authCookie(account(Role.ADMIN));
        MockMultipartFile poster = new MockMultipartFile("poster", "p.png", "image/png", new byte[]{1, 2, 3});
        mvc.perform(multipart("/admin/movies/save").file(poster).with(csrf()).cookie(admin)
                        .param("title", "Phim Mới Toanh " + unique()).param("durationMinutes", "95")
                        .param("ageRating", "P").param("status", "COMING_SOON"))
                .andExpect(redirectedUrl("/admin/movies"));
        mvc.perform(multipart("/admin/movies/save").with(csrf()).cookie(admin)
                        .param("title", "").param("durationMinutes", "5").param("ageRating", "P").param("status", "COMING_SOON"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Vui lòng nhập tên phim")));
    }

    @Test
    void adminCreatesShowtimesAndSeesOverlapError() throws Exception {
        Showtime st = showtimeTomorrow();
        Cookie admin = authCookie(account(Role.ADMIN));
        String day = st.getStartTime().toLocalDate().plusDays(1).toString();
        mvc.perform(post("/admin/showtimes").with(csrf()).cookie(admin)
                        .param("movieId", st.getMovie().getId().toString()).param("roomId", st.getRoom().getId().toString())
                        .param("fromDate", day).param("times", "10:00, 14:00").param("format", "TWO_D"))
                .andExpect(flash().attribute("success", "Đã tạo 2 suất chiếu"));
        mvc.perform(post("/admin/showtimes").with(csrf()).cookie(admin)
                        .param("movieId", st.getMovie().getId().toString()).param("roomId", st.getRoom().getId().toString())
                        .param("fromDate", day).param("times", "10:30").param("format", "TWO_D"))
                .andExpect(flash().attribute("error", containsString("trùng giờ")));
    }

    @Test
    void staffSellsAtCounter() throws Exception {
        Showtime st = showtimeTomorrow();
        Cookie staff = authCookie(account(Role.STAFF));
        Long seat = freeSeats(st.getId(), 1).get(0);
        mvc.perform(post("/staff/sell/" + st.getId()).with(csrf()).cookie(staff)
                        .param("showtimeSeatIds", seat.toString()).param("customerName", "Khách"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/staff/tickets/*"));
    }

    @Test
    void checkInPageScansWithCameraAndConfirmsTicket() throws Exception {
        User staff = account(Role.STAFF);
        Showtime st = showtime(AgeRating.P,
                LocalDateTime.now().plusMinutes(30).withSecond(0).withNano(0));
        CounterSaleForm form = new CounterSaleForm();
        form.setShowtimeSeatIds(freeSeats(st.getId(), 1));
        form.setCustomerName("Khách lẻ");
        Booking sold = bookingService.sellAtCounter(staff.getId(), st.getId(), form);
        Cookie cookie = authCookie(staff);

        mvc.perform(get("/staff/checkin").param("code", sold.getCode().toLowerCase()).cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/webjars/html5-qrcode/2.3.8/html5-qrcode.min.js")))
                .andExpect(content().string(containsString("/js/checkin-scanner.js")))
                .andExpect(content().string(containsString("data-current-code=\"" + sold.getCode() + "\"")))
                // The confirm form must carry the CSRF token, otherwise the POST is refused.
                .andExpect(content().string(containsString(
                        "action=\"/staff/checkin\" class=\"mt-3\"><input type=\"hidden\" name=\"_csrf\"")));
        mvc.perform(post("/staff/checkin").with(csrf()).cookie(cookie).param("code", sold.getCode()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("success", containsString(sold.getCode())));
    }

    @Test
    void scannerLibraryIsServedLocally() throws Exception {
        mvc.perform(get("/webjars/html5-qrcode/2.3.8/html5-qrcode.min.js")).andExpect(status().isOk());
        mvc.perform(get("/js/checkin-scanner.js")).andExpect(status().isOk());
    }

    @Test
    void adminPostFormsCarryCsrfToken() throws Exception {
        Cookie admin = authCookie(account(Role.ADMIN));
        mvc.perform(get("/admin/prices").cookie(admin))
                .andExpect(content().string(containsString(
                        "action=\"/admin/prices\" class=\"panel\"><input type=\"hidden\" name=\"_csrf\"")));
    }

    @Test
    void seatPagesAreReadyForLiveUpdates() throws Exception {
        Showtime st = showtimeTomorrow();
        User other = member(30);
        Long heldSeat = freeSeats(st.getId(), 1).get(0);
        bookingService.holdSeats(other.getId(), st.getId(), List.of(heldSeat));
        Cookie member = authCookie(member(20));
        mvc.perform(get("/booking/showtime/" + st.getId()).cookie(member))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-showtime-id=\"" + st.getId() + "\"")))
                .andExpect(content().string(containsString("data-role=\"available-count\"")))
                .andExpect(content().string(containsString("đang có người giữ")));
        mvc.perform(get("/staff/sell/" + st.getId()).cookie(authCookie(account(Role.STAFF))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-showtime-id=\"" + st.getId() + "\"")));
    }

    @Test
    void ticketsShowFadedLogoBehindText() throws Exception {
        User staff = account(Role.STAFF);
        Showtime st = showtimeTomorrow();
        CounterSaleForm form = new CounterSaleForm();
        form.setShowtimeSeatIds(freeSeats(st.getId(), 1));
        form.setCustomerName("Khách lẻ");
        Booking sold = bookingService.sellAtCounter(staff.getId(), st.getId(), form);
        mvc.perform(get("/staff/tickets/" + sold.getCode()).cookie(authCookie(staff)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"ticket-watermark\" src=\"/images/logo-watermark.svg\"")));

        User member = member(22);
        Booking online = bookingService.holdSeats(member.getId(), st.getId(), freeSeats(st.getId(), 1));
        bookingService.payWithMock(online.getCode(), member.getId());
        mvc.perform(get("/booking/" + online.getCode()).cookie(authCookie(member)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class=\"ticket-watermark\" src=\"/images/logo-watermark.svg\"")));
        mvc.perform(get("/images/logo-watermark.svg")).andExpect(status().isOk());
    }

    @Test
    void couplePriceIsShownButNotEditable() throws Exception {
        Cookie admin = authCookie(account(Role.ADMIN));
        String page = mvc.perform(get("/admin/prices").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("= 2 × giá VIP (tự động)")))
                .andReturn().getResponse().getContentAsString();
        // Only standard and VIP prices have inputs: 2 formats x 2 day types x 2 time slots x 2 seat types.
        assertThat(page.split("name=\"price_", -1).length - 1).isEqualTo(16);
        mvc.perform(get("/prices"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ghế đôi (dành cho hai người) có giá bằng 2 ghế VIP")));
    }

    @Test
    void dateStripsCoverTwoWeeks() throws Exception {
        LocalDate lastDay = LocalDate.now().plusDays(13);
        mvc.perform(get("/schedule"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/schedule?date=" + lastDay)))
                .andExpect(content().string(containsString("data-booking-days=\"14\"")));
        mvc.perform(get("/staff").cookie(authCookie(account(Role.STAFF))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("date=" + lastDay)));
    }

    @Test
    void upcomingMoviePicksAppearOnHomeMovieAndTicketPages() throws Exception {
        int n = unique();
        Movie upcoming = new Movie();
        upcoming.setTitle("Phim sắp chiếu " + n);
        upcoming.setSlug("phim-sap-chieu-" + n);
        upcoming.setDurationMinutes(110);
        upcoming.setAgeRating(AgeRating.P);
        upcoming.setStatus(MovieStatus.COMING_SOON);
        upcoming.setReleaseDate(LocalDate.now().plusDays(1));
        movieRepository.save(upcoming);

        mvc.perform(get("/"))
                .andExpect(content().string(containsString("Phim sắp chiếu bạn có thể thích")))
                .andExpect(content().string(containsString("Phim sắp chiếu " + n)));
        // On its own detail page the movie is not suggested to itself.
        String detail = mvc.perform(get("/movies/" + upcoming.getSlug())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int start = detail.indexOf("comingSoonPicksTitle");
        String picks = start < 0 ? "" : detail.substring(start, detail.indexOf("</section>", start));
        assertThat(picks).doesNotContain("/movies/" + upcoming.getSlug() + "\"");

        Showtime st = showtimeTomorrow();
        User member = member(22);
        Booking booking = bookingService.holdSeats(member.getId(), st.getId(), freeSeats(st.getId(), 1));
        mvc.perform(get("/booking/" + booking.getCode()).cookie(authCookie(member)))
                .andExpect(content().string(not(containsString("Phim sắp chiếu bạn có thể thích"))));
        bookingService.payWithMock(booking.getCode(), member.getId());
        mvc.perform(get("/booking/" + booking.getCode()).cookie(authCookie(member)))
                .andExpect(content().string(containsString("Phim sắp chiếu bạn có thể thích")))
                .andExpect(content().string(containsString("Phim sắp chiếu " + n)));
    }

    @Test
    void firstVisitAsksWhichCinemaUntilOneIsChosen() throws Exception {
        Showtime st = showtimeTomorrow();
        Cinema cinema = st.getRoom().getCinema();
        cinema.setLatitude(21.0368);
        cinema.setLongitude(105.7820);
        cinemaRepository.save(cinema);

        mvc.perform(get("/"))
                .andExpect(content().string(containsString("id=\"cinemaChooser\"")))
                .andExpect(content().string(containsString("Dùng vị trí của tôi")))
                .andExpect(content().string(containsString("data-lat=\"21.0368\"")))
                .andExpect(content().string(containsString("/js/cinema-chooser.js")));
        mvc.perform(get("/").cookie(new Cookie("BB_CINEMA", cinema.getId().toString())))
                .andExpect(content().string(not(containsString("id=\"cinemaChooser\""))));
        // A cookie for a cinema that no longer exists counts as not chosen.
        mvc.perform(get("/").cookie(new Cookie("BB_CINEMA", "999999")))
                .andExpect(content().string(containsString("id=\"cinemaChooser\"")));
        // Never on the internal pages.
        mvc.perform(get("/staff").cookie(authCookie(account(Role.STAFF))))
                .andExpect(content().string(not(containsString("cinemaChooser"))));
        mvc.perform(get("/internal/login"))
                .andExpect(content().string(not(containsString("cinemaChooser"))));
    }

    @Test
    void adminSavesCinemaCoordinates() throws Exception {
        Cookie admin = authCookie(account(Role.ADMIN));
        int n = unique();
        mvc.perform(post("/admin/cinemas/save").with(csrf()).cookie(admin)
                        .param("name", "Rạp toạ độ " + n).param("address", "Hà Nội").param("active", "true")
                        .param("latitude", "21.03"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("cả vĩ độ và kinh độ")));
        mvc.perform(post("/admin/cinemas/save").with(csrf()).cookie(admin)
                        .param("name", "Rạp toạ độ " + n).param("address", "Hà Nội").param("active", "true")
                        .param("latitude", "95").param("longitude", "105.78"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Vĩ độ từ -90 đến 90")));
        mvc.perform(post("/admin/cinemas/save").with(csrf()).cookie(admin)
                        .param("name", "Rạp toạ độ " + n).param("address", "Hà Nội").param("active", "true")
                        .param("latitude", "21.03").param("longitude", "105.78"))
                .andExpect(status().is3xxRedirection());
        Cinema saved = cinemaRepository.findAll().stream()
                .filter(c -> c.getName().equals("Rạp toạ độ " + n)).findFirst().orElseThrow();
        assertThat(saved.getLatitude()).isEqualTo(21.03);
        assertThat(saved.getLongitude()).isEqualTo(105.78);
    }

    @Test
    void cinemasPageShowsMapWithCinemaPositions() throws Exception {
        Showtime st = showtimeTomorrow();
        Cinema cinema = st.getRoom().getCinema();
        cinema.setLatitude(21.0368);
        cinema.setLongitude(105.782);
        cinemaRepository.save(cinema);
        mvc.perform(get("/cinemas"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"cinemaMap\"")))
                .andExpect(content().string(containsString("/webjars/leaflet/1.9.4/dist/leaflet.js")))
                .andExpect(content().string(containsString("data-lat=\"21.0368\"")))
                .andExpect(content().string(containsString("Xem trên bản đồ")));
        mvc.perform(get("/webjars/leaflet/1.9.4/dist/leaflet.js")).andExpect(status().isOk());
        mvc.perform(get("/js/cinema-map.js")).andExpect(status().isOk());
    }

    @Test
    void showtimeLanguageIsChosenByAdminAndShownToCustomers() throws Exception {
        Showtime st = showtimeTomorrow(); // test movies have no language: subtitled by default
        assertThat(st.getVersionLabel()).isEqualTo("2D Phụ đề");
        Cookie admin = authCookie(account(Role.ADMIN));
        String day = st.getStartTime().toLocalDate().plusDays(2).toString();
        mvc.perform(post("/admin/showtimes").with(csrf()).cookie(admin)
                        .param("movieId", st.getMovie().getId().toString()).param("roomId", st.getRoom().getId().toString())
                        .param("fromDate", day).param("times", "09:00").param("format", "TWO_D").param("language", "DUBBED"))
                .andExpect(flash().attribute("success", "Đã tạo 1 suất chiếu"));
        mvc.perform(get("/api/movies/" + st.getMovie().getId() + "/showtimes")
                        .param("cinemaId", st.getRoom().getCinema().getId().toString()).param("date", day))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].language").value("Lồng tiếng"));
        mvc.perform(get("/movies/" + st.getMovie().getSlug()).param("date", day)
                        .cookie(new Cookie("BB_CINEMA", st.getRoom().getCinema().getId().toString())))
                .andExpect(content().string(containsString("2D Lồng tiếng")));
    }
}
