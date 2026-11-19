package com.bbcinema;

import com.bbcinema.config.AppProperties;
import com.bbcinema.entity.*;
import com.bbcinema.entity.enums.*;

import java.math.BigDecimal;
import java.time.*;

/** Small builders shared by unit tests. */
public final class TestFixtures {

    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    /** Thursday 19/11/2026 10:00 in Hanoi. */
    public static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 19, 10, 0);

    private TestFixtures() {
    }

    public static Clock clock() {
        return clockAt(NOW);
    }

    public static Clock clockAt(LocalDateTime time) {
        return Clock.fixed(time.atZone(ZONE).toInstant(), ZONE);
    }

    public static AppProperties properties() {
        return new AppProperties(
                "http://localhost:8080",
                "Asia/Ho_Chi_Minh",
                new AppProperties.Booking(5, 2, 15, 60),
                new AppProperties.Points(5, 1000, 50),
                new AppProperties.Jwt("Ymljb2luZW1hLWRldmVsb3BtZW50LXNlY3JldC1rZXktY2hhbmdlLW1lLTIwMjY=", 24, "BB_TOKEN"),
                new AppProperties.Upload("target/test-uploads"),
                new AppProperties.Mail(false, "test@bbcinema.local"),
                new AppProperties.VnPay("TESTCODE", "SECRETKEY123", "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html", "/payment/vnpay-return"),
                false,
                AppProperties.Membership.DEFAULT);
    }

    public static User user(long id, LocalDate birthDate) {
        User u = new User();
        u.setId(id);
        u.setEmail("user" + id + "@test.vn");
        u.setFullName("User " + id);
        u.setPasswordHash("x");
        u.setBirthDate(birthDate);
        u.setRole(Role.USER);
        return u;
    }

    public static Movie movie(long id, int duration, AgeRating rating) {
        Movie m = new Movie();
        m.setId(id);
        m.setTitle("Movie " + id);
        m.setSlug("movie-" + id);
        m.setDurationMinutes(duration);
        m.setAgeRating(rating);
        m.setStatus(MovieStatus.NOW_SHOWING);
        return m;
    }

    public static Room room(long id) {
        Cinema c = new Cinema();
        c.setId(1L);
        c.setName("BBCinema Test");
        c.setAddress("Hà Nội");
        Room r = new Room();
        r.setId(id);
        r.setCinema(c);
        r.setName("Phòng " + id);
        r.setRowCount(2);
        r.setSeatsPerRow(3);
        return r;
    }

    public static Showtime showtime(long id, Movie movie, Room room, LocalDateTime start) {
        Showtime s = new Showtime();
        s.setId(id);
        s.setMovie(movie);
        s.setRoom(room);
        s.setStartTime(start);
        s.setEndTime(start.plusMinutes(movie.getDurationMinutes() + 15L));
        s.setFormat(ScreenFormat.TWO_D);
        return s;
    }

    public static ShowtimeSeat seat(long id, Showtime showtime, String row, int number, long price) {
        Seat seat = new Seat(row, number, SeatType.STANDARD);
        seat.setId(id);
        seat.setRoom(showtime.getRoom());
        ShowtimeSeat ss = new ShowtimeSeat();
        ss.setId(id);
        ss.setShowtime(showtime);
        ss.setSeat(seat);
        ss.setPrice(BigDecimal.valueOf(price));
        return ss;
    }

    public static Promotion promotion(String code, int percent, Long max, Long min, LocalDate from, LocalDate to, Integer limit) {
        Promotion p = new Promotion();
        p.setId(1L);
        p.setCode(code);
        p.setDiscountPercent(percent);
        p.setMaxDiscount(max == null ? null : BigDecimal.valueOf(max));
        p.setMinOrder(min == null ? null : BigDecimal.valueOf(min));
        p.setValidFrom(from);
        p.setValidTo(to);
        p.setUsageLimit(limit);
        return p;
    }
}
