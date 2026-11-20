package com.bbcinema.integration;

import com.bbcinema.entity.Booking;
import com.bbcinema.entity.Holiday;
import com.bbcinema.entity.Showtime;
import com.bbcinema.entity.ShowtimeSeat;
import com.bbcinema.entity.TicketPrice;
import com.bbcinema.entity.User;
import com.bbcinema.entity.enums.*;
import com.bbcinema.repository.HolidayRepository;
import com.bbcinema.repository.TicketPriceRepository;
import com.bbcinema.service.PricingService.HolidayCell;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Public holidays: the admin list, holiday prices, and re-pricing the free seats of showtimes already scheduled. */
class HolidayPricingIntegrationTest extends IntegrationTestBase {

    private static final BigDecimal HOLIDAY_STANDARD = BigDecimal.valueOf(77_000);
    private static final BigDecimal HOLIDAY_VIP = BigDecimal.valueOf(88_000);

    @Autowired HolidayRepository holidayRepository;
    @Autowired TicketPriceRepository ticketPriceRepository;

    /** The test database is shared by all test classes: leave no holiday behind. */
    @AfterEach
    void removeHolidays() {
        holidayRepository.deleteAll();
    }

    /** A Thursday far enough ahead that no other test schedules a show on it. */
    private LocalDate quietThursday() {
        LocalDate date = LocalDate.now().plusYears(3).plusWeeks(unique());
        while (date.getDayOfWeek() != DayOfWeek.THURSDAY) {
            date = date.plusDays(1);
        }
        return date;
    }

    private void setHolidayPrices() {
        pricingService.updatePrices(Map.of(), Map.of(
                new HolidayCell(SeatType.STANDARD, ScreenFormat.TWO_D, TimeSlot.FROM_17), HOLIDAY_STANDARD,
                new HolidayCell(SeatType.VIP, ScreenFormat.TWO_D, TimeSlot.FROM_17), HOLIDAY_VIP));
    }

    private List<ShowtimeSeat> seats(Long showtimeId) {
        return showtimeSeatRepository.findSeatMap(showtimeId);
    }

    private ShowtimeSeat seat(Long id) {
        return showtimeSeatRepository.findById(id).orElseThrow();
    }

    @Test
    void addingAndRemovingAHolidayRepricesOnlyFreeSeatsOfThatDate() throws Exception {
        setHolidayPrices();
        LocalDate date = quietThursday();
        Showtime st = showtime(AgeRating.P, date.atTime(19, 0));
        Showtime nextDay = showtime(AgeRating.P, date.plusDays(1).atTime(19, 0));
        BigDecimal weekdayStandard = pricingService.priceFor(SeatType.STANDARD, ScreenFormat.TWO_D, date.atTime(19, 0));
        BigDecimal weekdayVip = pricingService.priceFor(SeatType.VIP, ScreenFormat.TWO_D, date.atTime(19, 0));

        // One seat held by a member, one sold: they must keep the price the customer was shown.
        List<Long> free = freeSeats(st.getId(), 2);
        Booking held = bookingService.holdSeats(member(25).getId(), st.getId(), List.of(free.get(0)));
        ShowtimeSeat sold = seat(free.get(1));
        sold.markBooked();
        showtimeSeatRepository.save(sold);
        BigDecimal heldTicketAmount = held.getTicketAmount();

        Cookie admin = authCookie(account(Role.ADMIN));
        mvc.perform(post("/admin/prices/holidays").with(csrf()).cookie(admin)
                        .param("date", date.toString()).param("name", "Ngày lễ thử"))
                .andExpect(redirectedUrl("/admin/prices/holidays"))
                .andExpect(flash().attribute("success", containsString("cho 10 ghế còn trống")));

        for (ShowtimeSeat ss : seats(st.getId())) {
            if (ss.getId().equals(free.get(0)) || ss.getId().equals(free.get(1))) {
                assertThat(ss.getPrice()).isEqualByComparingTo(weekdayStandard);
            } else {
                assertThat(ss.getPrice()).isEqualByComparingTo(ss.getSeat().getType() == SeatType.VIP ? HOLIDAY_VIP : HOLIDAY_STANDARD);
            }
        }
        assertThat(seat(free.get(0)).getStatus()).isEqualTo(SeatStatus.HELD);
        assertThat(seat(free.get(1)).getStatus()).isEqualTo(SeatStatus.BOOKED);
        assertThat(bookingRepository.findById(held.getId()).orElseThrow().getTicketAmount()).isEqualByComparingTo(heldTicketAmount);
        // The next day is not a holiday.
        assertThat(seats(nextDay.getId())).allSatisfy(ss -> assertThat(ss.getPrice())
                .isEqualByComparingTo(ss.getSeat().getType() == SeatType.VIP ? weekdayVip : weekdayStandard));

        // A held seat that comes back on sale takes the holiday price of the other free seats.
        bookingService.cancel(held.getCode(), held.getUser().getId(), Role.USER);
        assertThat(seat(free.get(0)).getPrice()).isEqualByComparingTo(HOLIDAY_STANDARD);

        Holiday holiday = holidayRepository.findByDate(date).orElseThrow();
        mvc.perform(post("/admin/prices/holidays/" + holiday.getId() + "/delete").with(csrf()).cookie(admin))
                .andExpect(redirectedUrl("/admin/prices/holidays"))
                .andExpect(flash().attribute("success", containsString("cho 11 ghế còn trống")));
        assertThat(holidayRepository.existsByDate(date)).isFalse();
        for (ShowtimeSeat ss : seats(st.getId())) {
            if (ss.getId().equals(free.get(1))) {
                assertThat(ss.getPrice()).isEqualByComparingTo(weekdayStandard); // sold seat never changes
            } else {
                assertThat(ss.getPrice()).isEqualByComparingTo(ss.getSeat().getType() == SeatType.VIP ? weekdayVip : weekdayStandard);
            }
        }
    }

    @Test
    void showtimeCreatedOnAHolidayGetsHolidayPricesAndCoupleIsTwoVip() {
        setHolidayPrices();
        LocalDate date = quietThursday();
        holidayRepository.save(new Holiday(date, "Ngày lễ thử"));

        Showtime st = showtime(AgeRating.P, date.atTime(18, 0));

        assertThat(seats(st.getId())).allSatisfy(ss -> assertThat(ss.getPrice())
                .isEqualByComparingTo(ss.getSeat().getType() == SeatType.VIP ? HOLIDAY_VIP : HOLIDAY_STANDARD));
        assertThat(pricingService.priceFor(SeatType.COUPLE, ScreenFormat.TWO_D, date.atTime(18, 0)))
                .isEqualByComparingTo(HOLIDAY_VIP.multiply(BigDecimal.TWO));
    }

    @Test
    void duplicateDateAndMissingNameAreRejected() throws Exception {
        LocalDate date = quietThursday();
        Cookie admin = authCookie(account(Role.ADMIN));
        mvc.perform(post("/admin/prices/holidays").with(csrf()).cookie(admin)
                        .param("date", date.toString()).param("name", "Quốc khánh 2/9"))
                .andExpect(redirectedUrl("/admin/prices/holidays"));
        mvc.perform(post("/admin/prices/holidays").with(csrf()).cookie(admin)
                        .param("date", date.toString()).param("name", "Lần hai"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("đã có trong danh sách ngày lễ (Quốc khánh 2/9)")));
        mvc.perform(post("/admin/prices/holidays").with(csrf()).cookie(admin)
                        .param("date", date.plusDays(1).toString()).param("name", "  "))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Vui lòng nhập tên ngày lễ")));
        mvc.perform(post("/admin/prices/holidays").with(csrf()).cookie(admin).param("name", "Không có ngày"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Vui lòng chọn ngày")));
        assertThat(holidayRepository.findAll()).extracting(Holiday::getName).containsExactly("Quốc khánh 2/9");
    }

    @Test
    void onlyTheGeneralManagerManagesHolidays() throws Exception {
        Holiday holiday = holidayRepository.save(new Holiday(quietThursday(), "Ngày lễ thử"));
        Cookie manager = authCookie(account(Role.MANAGER));
        Cookie staff = authCookie(account(Role.STAFF));
        Cookie member = authCookie(member(30));
        for (Cookie cookie : new Cookie[]{manager, staff, member}) {
            mvc.perform(get("/admin/prices/holidays").cookie(cookie)).andExpect(status().isForbidden());
            mvc.perform(post("/admin/prices/holidays").with(csrf()).cookie(cookie)
                    .param("date", LocalDate.now().plusYears(9).toString()).param("name", "X")).andExpect(status().isForbidden());
            mvc.perform(post("/admin/prices/holidays/" + holiday.getId() + "/delete").with(csrf()).cookie(cookie))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/admin/prices/holidays")).andExpect(redirectedUrl("/internal/login?redirect=%2Fadmin%2Fprices%2Fholidays"));
        assertThat(holidayRepository.count()).isEqualTo(1);
        mvc.perform(get("/admin/prices/holidays").cookie(authCookie(account(Role.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ngày lễ thử")));
    }

    @Test
    void adminSetsAHolidayPriceForTheFirstTime() throws Exception {
        ticketPriceRepository.findBySeatTypeAndFormatAndDayTypeAndTimeSlot(SeatType.STANDARD, ScreenFormat.THREE_D, DayType.HOLIDAY, TimeSlot.BEFORE_17)
                .ifPresent(ticketPriceRepository::delete);
        LocalDate date = quietThursday();
        Showtime st = showtime(AgeRating.P, date.atTime(10, 0)); // 2D, before 17h
        holidayRepository.save(new Holiday(date, "Ngày lễ thử"));
        Cookie admin = authCookie(account(Role.ADMIN));
        String page = mvc.perform(get("/admin/prices").cookie(admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(page).contains("name=\"holiday_STANDARD_THREE_D_BEFORE_17\"").contains("Chưa đặt: dùng giá cuối tuần");

        mvc.perform(post("/admin/prices").with(csrf()).cookie(admin)
                        .param("holiday_STANDARD_THREE_D_BEFORE_17", "123000")
                        .param("holiday_STANDARD_TWO_D_BEFORE_17", "71000"))
                .andExpect(redirectedUrl("/admin/prices"))
                .andExpect(flash().attribute("success", containsString("ghế còn trống của các suất chiếu ngày lễ sắp tới")));

        assertThat(ticketPriceRepository.findBySeatTypeAndFormatAndDayTypeAndTimeSlot(
                SeatType.STANDARD, ScreenFormat.THREE_D, DayType.HOLIDAY, TimeSlot.BEFORE_17).orElseThrow().getPrice())
                .isEqualByComparingTo("123000");
        // The upcoming holiday show already scheduled is re-priced with the new holiday price.
        assertThat(seats(st.getId()).stream().filter(ss -> ss.getSeat().getType() == SeatType.STANDARD))
                .allSatisfy(ss -> assertThat(ss.getPrice()).isEqualByComparingTo("71000"));

        mvc.perform(post("/admin/prices").with(csrf()).cookie(admin).param("holiday_COUPLE_TWO_D_BEFORE_17", "1"))
                .andExpect(flash().attribute("error", containsString("tự động")));
        mvc.perform(post("/admin/prices").with(csrf()).cookie(admin).param("holiday_NOPE", "1"))
                .andExpect(flash().attribute("error", "Ô giá ngày lễ không hợp lệ"));
    }

    @Test
    void publicPricesShowTheHolidayColumnAndUpcomingHolidays() throws Exception {
        LocalDate soon = LocalDate.now().plusMonths(2);
        holidayRepository.save(new Holiday(soon, "Ngày lễ công khai"));
        holidayRepository.save(new Holiday(LocalDate.now().plusMonths(13), "Quá xa"));
        holidayRepository.save(new Holiday(LocalDate.now().minusDays(3), "Đã qua"));

        mvc.perform(get("/prices"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(">Ngày lễ<")))
                .andExpect(content().string(containsString("Ngày lễ sắp tới")))
                .andExpect(content().string(containsString("Ngày lễ công khai")))
                .andExpect(content().string(containsString(soon.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")))))
                .andExpect(content().string(not(containsString("Quá xa"))))
                .andExpect(content().string(not(containsString("Đã qua"))))
                .andExpect(content().string(containsString("áp dụng giá cuối tuần")));
    }

    @Test
    void scheduleDateStripMarksHolidays() throws Exception {
        LocalDate date = LocalDate.now().plusDays(3);
        holidayRepository.save(new Holiday(date, "Ngày lễ trên lịch"));

        String page = mvc.perform(get("/schedule")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page.split("class=\"holiday-tag\"", -1).length - 1).isEqualTo(1);
    }

    @Test
    void expiredButNotReleasedHoldIsRepricedLikeAFreeSeat() throws Exception {
        setHolidayPrices();
        LocalDate date = quietThursday();
        Showtime st = showtime(AgeRating.P, date.atTime(19, 0));
        List<Long> free = freeSeats(st.getId(), 2);
        Booking expired = bookingService.holdSeats(member(25).getId(), st.getId(), List.of(free.get(0)));
        Booking active = bookingService.holdSeats(member(26).getId(), st.getId(), List.of(free.get(1)));
        // The hold ran out a minute ago, but the cleanup job has not released it yet.
        ShowtimeSeat expiredSeat = seat(free.get(0));
        expiredSeat.setHoldExpiresAt(LocalDateTime.now().minusMinutes(1));
        showtimeSeatRepository.save(expiredSeat);
        Booking expiredBooking = bookingRepository.findById(expired.getId()).orElseThrow();
        expiredBooking.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        bookingRepository.save(expiredBooking);
        BigDecimal weekdayStandard = seat(free.get(1)).getPrice();

        Cookie admin = authCookie(account(Role.ADMIN));
        mvc.perform(post("/admin/prices/holidays").with(csrf()).cookie(admin)
                        .param("date", date.toString()).param("name", "Ngày lễ thử"))
                .andExpect(flash().attribute("success", containsString("cho 11 ghế còn trống")));

        assertThat(seat(free.get(0)).getPrice()).isEqualByComparingTo(HOLIDAY_STANDARD); // a customer can pick it now
        assertThat(seat(free.get(0)).getStatus()).isEqualTo(SeatStatus.HELD);
        assertThat(seat(free.get(1)).getPrice()).isEqualByComparingTo(weekdayStandard); // still really held
        assertThat(bookingRepository.findById(active.getId()).orElseThrow().getTicketAmount()).isEqualByComparingTo(weekdayStandard);
    }

    @Test
    void clearingAStoredHolidayPriceGoesBackToTheWeekendPrice() throws Exception {
        setHolidayPrices();
        LocalDate date = quietThursday();
        holidayRepository.save(new Holiday(date, "Ngày lễ thử"));
        Showtime st = showtime(AgeRating.P, date.atTime(19, 0));
        assertThat(seats(st.getId())).filteredOn(ss -> ss.getSeat().getType() == SeatType.STANDARD)
                .allSatisfy(ss -> assertThat(ss.getPrice()).isEqualByComparingTo(HOLIDAY_STANDARD));
        TicketPrice holidayRow = ticketPriceRepository.findBySeatTypeAndFormatAndDayTypeAndTimeSlot(
                SeatType.STANDARD, ScreenFormat.TWO_D, DayType.HOLIDAY, TimeSlot.FROM_17).orElseThrow();
        BigDecimal weekendStandard = ticketPriceRepository.findBySeatTypeAndFormatAndDayTypeAndTimeSlot(
                SeatType.STANDARD, ScreenFormat.TWO_D, DayType.WEEKEND, TimeSlot.FROM_17).orElseThrow().getPrice();

        Cookie admin = authCookie(account(Role.ADMIN));
        mvc.perform(post("/admin/prices").with(csrf()).cookie(admin).param("price_" + holidayRow.getId(), ""))
                .andExpect(redirectedUrl("/admin/prices"))
                .andExpect(flash().attribute("success", containsString("Đã cập nhật giá cho 8 ghế còn trống")));

        assertThat(ticketPriceRepository.findById(holidayRow.getId())).isEmpty();
        assertThat(seats(st.getId())).filteredOn(ss -> ss.getSeat().getType() == SeatType.STANDARD)
                .allSatisfy(ss -> assertThat(ss.getPrice()).isEqualByComparingTo(weekendStandard));
        assertThat(seats(st.getId())).filteredOn(ss -> ss.getSeat().getType() == SeatType.VIP)
                .allSatisfy(ss -> assertThat(ss.getPrice()).isEqualByComparingTo(HOLIDAY_VIP));

        // An empty weekday or weekend price is still refused, and nothing is saved.
        TicketPrice weekday = ticketPriceRepository.findBySeatTypeAndFormatAndDayTypeAndTimeSlot(
                SeatType.STANDARD, ScreenFormat.TWO_D, DayType.WEEKDAY, TimeSlot.FROM_17).orElseThrow();
        mvc.perform(post("/admin/prices").with(csrf()).cookie(admin).param("price_" + weekday.getId(), " "))
                .andExpect(flash().attribute("error", "Giá vé phải là số"));
        assertThat(ticketPriceRepository.findById(weekday.getId()).orElseThrow().getPrice()).isEqualByComparingTo(weekday.getPrice());
    }

    @Test
    void savingTheGridWithoutHolidayChangesDoesNotMentionHolidayRepricing() throws Exception {
        TicketPrice weekday = ticketPriceRepository.findBySeatTypeAndFormatAndDayTypeAndTimeSlot(
                SeatType.STANDARD, ScreenFormat.TWO_D, DayType.WEEKDAY, TimeSlot.FROM_17).orElseThrow();
        mvc.perform(post("/admin/prices").with(csrf()).cookie(authCookie(account(Role.ADMIN)))
                        .param("price_" + weekday.getId(), String.valueOf(weekday.getPrice().longValue())))
                .andExpect(flash().attribute("success",
                        "Đã lưu bảng giá. Giá ngày thường và cuối tuần áp dụng cho các suất chiếu tạo sau."));
    }

    @Test
    void pastHolidayIsRejectedButTodayIsAllowed() throws Exception {
        Cookie admin = authCookie(account(Role.ADMIN));
        mvc.perform(post("/admin/prices/holidays").with(csrf()).cookie(admin)
                        .param("date", LocalDate.now().minusDays(1).toString()).param("name", "Hôm qua"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Không thể thêm ngày lễ đã qua")));
        mvc.perform(post("/admin/prices/holidays").with(csrf()).cookie(admin)
                        .param("date", LocalDate.now().toString()).param("name", "Hôm nay"))
                .andExpect(redirectedUrl("/admin/prices/holidays"));
        assertThat(holidayRepository.findAll()).extracting(Holiday::getName).containsExactly("Hôm nay");
        mvc.perform(get("/admin/prices/holidays").cookie(admin))
                .andExpect(content().string(containsString("Suất đã bắt đầu không đổi giá.")));
    }

    // ------------------------------------------------------------------ a seat coming back on sale

    /** Holds a seat, then gives it an old price, as if its showtime was re-priced while it was held. */
    private Booking holdAtOldPrice(Showtime st, Long seatId, BigDecimal oldPrice) {
        Booking booking = bookingService.holdSeats(member(30).getId(), st.getId(), List.of(seatId));
        ShowtimeSeat held = seat(seatId);
        held.setPrice(oldPrice);
        showtimeSeatRepository.save(held);
        return booking;
    }

    private void cancel(Booking booking) {
        bookingService.cancel(booking.getCode(), booking.getUser().getId(), Role.USER);
    }

    @Test
    void releasedSeatTakesThePriceTheOtherFreeSeatsAgreeOn() {
        Showtime st = showtimeTomorrow();
        Long seatId = freeSeats(st.getId(), 1).get(0);
        BigDecimal current = seat(seatId).getPrice();
        Booking booking = holdAtOldPrice(st, seatId, BigDecimal.valueOf(12_345));

        cancel(booking);

        assertThat(seat(seatId).getPrice()).isEqualByComparingTo(current);
        assertThat(seat(seatId).getStatus()).isEqualTo(SeatStatus.AVAILABLE);
    }

    @Test
    void releasedSeatKeepsItsPriceWhenFreeSeatsDisagree() {
        Showtime st = showtimeTomorrow();
        List<Long> ids = freeSeats(st.getId(), 2);
        Booking booking = holdAtOldPrice(st, ids.get(0), BigDecimal.valueOf(12_345));
        ShowtimeSeat other = seat(ids.get(1));
        other.setPrice(BigDecimal.valueOf(11_111));
        showtimeSeatRepository.save(other);

        cancel(booking);

        assertThat(seat(ids.get(0)).getPrice()).isEqualByComparingTo("12345");
    }

    @Test
    void releasedSeatKeepsItsPriceWhenNoOtherSeatOfItsTypeIsFree() {
        Showtime st = showtimeTomorrow();
        Long seatId = freeSeats(st.getId(), 1).get(0);
        Booking booking = holdAtOldPrice(st, seatId, BigDecimal.valueOf(12_345));
        for (ShowtimeSeat ss : seats(st.getId())) {
            if (!ss.getId().equals(seatId) && ss.getSeat().getType() == SeatType.STANDARD) {
                ss.markBooked();
                showtimeSeatRepository.save(ss);
            }
        }

        cancel(booking);

        assertThat(seat(seatId).getPrice()).isEqualByComparingTo("12345");
    }
}
