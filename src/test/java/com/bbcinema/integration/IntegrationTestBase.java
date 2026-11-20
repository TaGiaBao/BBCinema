package com.bbcinema.integration;

import com.bbcinema.dto.request.RoomForm;
import com.bbcinema.entity.*;
import com.bbcinema.entity.enums.*;
import com.bbcinema.repository.*;
import com.bbcinema.security.AppUserPrincipal;
import com.bbcinema.security.JwtService;
import com.bbcinema.service.*;
import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Starts the whole application on an in-memory H2 database and builds test data through the real services. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class IntegrationTestBase {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired protected MockMvc mvc;
    @Autowired protected UserService userService;
    @Autowired protected UserRepository userRepository;
    @Autowired protected CinemaRepository cinemaRepository;
    @Autowired protected RoomService roomService;
    @Autowired protected MovieRepository movieRepository;
    @Autowired protected PricingService pricingService;
    @Autowired protected ShowtimeService showtimeService;
    @Autowired protected ShowtimeSeatRepository showtimeSeatRepository;
    @Autowired protected BookingService bookingService;
    @Autowired protected BookingRepository bookingRepository;
    @Autowired protected ComboRepository comboRepository;
    @Autowired protected PromotionRepository promotionRepository;
    @Autowired protected JwtService jwtService;
    @Autowired protected com.bbcinema.service.ShiftService shiftService;

    protected int unique() {
        return COUNTER.incrementAndGet();
    }

    protected User member(int age) {
        int n = unique();
        User user = userService.createAccount("member" + n + "@test.vn", "Thành viên " + n, "Secret@123", Role.USER);
        user.setBirthDate(LocalDate.now().minusYears(age).minusDays(1));
        return userRepository.save(user);
    }

    /** A new counter manager (MANAGER, password Secret@123) opens a till shift for the staff member. */
    protected com.bbcinema.entity.CounterShift openShift(User staff, long openingFloat) {
        User manager = account(Role.MANAGER);
        return shiftService.open(staff.getId(), null, manager.getEmail(), "Secret@123", BigDecimal.valueOf(openingFloat));
    }

    /** A counter staff member already in an open shift (float 500,000đ). */
    protected User staffOnShift() {
        User staff = account(Role.STAFF);
        openShift(staff, 500_000);
        return staff;
    }

    protected User account(Role role) {
        int n = unique();
        return userService.createAccount(role.name().toLowerCase() + n + "@test.vn", role.getLabel() + " " + n, "Secret@123", role);
    }

    /** A cinema with one 3 x 4 room and a 2D showtime tomorrow at 19:00 (or the given time). */
    protected Showtime showtime(AgeRating rating, LocalDateTime start) {
        pricingService.ensureAllPricesExist(BigDecimal.valueOf(60_000));
        int n = unique();
        Cinema cinema = new Cinema();
        cinema.setName("Rạp test " + n);
        cinema.setAddress("Hà Nội");
        cinema = cinemaRepository.save(cinema);
        RoomForm form = new RoomForm();
        form.setCinemaId(cinema.getId());
        form.setName("Phòng " + n);
        form.setRowCount(3);
        form.setSeatsPerRow(4);
        form.setVipRows("C");
        Room room = roomService.create(form);
        Movie movie = new Movie();
        movie.setTitle("Phim test " + n);
        movie.setSlug("phim-test-" + n);
        movie.setDurationMinutes(100);
        movie.setAgeRating(rating);
        movie.setStatus(MovieStatus.NOW_SHOWING);
        movie = movieRepository.save(movie);
        return showtimeService.create(movie, room, start, ScreenFormat.TWO_D, false);
    }

    protected Showtime showtimeTomorrow() {
        return showtime(AgeRating.P, LocalDateTime.now().plusDays(1).withHour(19).withMinute(0).withSecond(0).withNano(0));
    }

    protected List<Long> freeSeats(Long showtimeId, int count) {
        return showtimeSeatRepository.findSeatMap(showtimeId).stream()
                .filter(ss -> ss.getStatus() == SeatStatus.AVAILABLE)
                .limit(count)
                .map(ShowtimeSeat::getId)
                .toList();
    }

    protected String token(User user) {
        return jwtService.generateToken(new AppUserPrincipal(user));
    }

    protected Cookie authCookie(User user) {
        return new Cookie("BB_TOKEN", token(user));
    }

    protected String bearer(User user) {
        return "Bearer " + token(user);
    }
}
