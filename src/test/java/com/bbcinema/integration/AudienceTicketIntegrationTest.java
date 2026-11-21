package com.bbcinema.integration;

import com.bbcinema.entity.Booking;
import com.bbcinema.entity.Showtime;
import com.bbcinema.entity.ShowtimeSeat;
import com.bbcinema.entity.User;
import com.bbcinema.entity.enums.AgeRating;
import com.bbcinema.entity.enums.AudienceType;
import com.bbcinema.entity.enums.DiscountType;
import com.bbcinema.entity.enums.MemberTier;
import com.bbcinema.entity.enums.Role;
import com.bbcinema.repository.AudienceDiscountRepository;
import com.bbcinema.service.AudienceTicketService;
import com.bbcinema.service.HolidayService;
import com.bbcinema.service.MemberCodeService;
import com.bbcinema.service.MembershipService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HSSV / child / senior tickets sold at the counter: prices, rules, member tier, printed ticket, admin settings. */
class AudienceTicketIntegrationTest extends IntegrationTestBase {

    @Autowired AudienceTicketService audienceTicketService;
    @Autowired AudienceDiscountRepository audienceDiscountRepository;
    @Autowired HolidayService holidayService;
    @Autowired MembershipService membershipService;
    @Autowired MemberCodeService memberCodeService;

    @AfterEach
    void backToDefaults() {
        audienceTicketService.save(settings(DiscountType.PERCENT, 20, true, false));
    }

    /** STUDENT with the given discount; CHILD 30% and SENIOR 30% always on. */
    private static Map<AudienceType, AudienceTicketService.Setting> settings(DiscountType type, long studentValue,
                                                                            boolean studentOn, boolean studentOnHolidays) {
        Map<AudienceType, AudienceTicketService.Setting> map = new EnumMap<>(AudienceType.class);
        map.put(AudienceType.STUDENT, new AudienceTicketService.Setting(studentOn, type, BigDecimal.valueOf(studentValue), studentOnHolidays));
        map.put(AudienceType.CHILD, new AudienceTicketService.Setting(true, DiscountType.PERCENT, BigDecimal.valueOf(30), false));
        map.put(AudienceType.SENIOR, new AudienceTicketService.Setting(true, DiscountType.PERCENT, BigDecimal.valueOf(30), false));
        return map;
    }

    private static String codeFromRedirect(String location) {
        return location.substring(location.lastIndexOf('/') + 1);
    }

    private Booking sold(String location) {
        return bookingRepository.findDetailedByCode(codeFromRedirect(location)).orElseThrow();
    }

    private ShowtimeSeat seat(Long id) {
        return showtimeSeatRepository.findById(id).orElseThrow();
    }

    /** A member with 1,200,000đ spent this year: SILVER, 5% off. */
    private User silverMember() {
        User member = member(30);
        Showtime st = showtimeTomorrow();
        Booking booking = bookingService.holdSeats(member.getId(), st.getId(), freeSeats(st.getId(), 1));
        booking = bookingService.payWithMock(booking.getCode(), member.getId());
        Booking stored = bookingRepository.findById(booking.getId()).orElseThrow();
        stored.setTotalAmount(BigDecimal.valueOf(1_200_000));
        bookingRepository.save(stored);
        assertThat(membershipService.tierOf(member)).isEqualTo(MemberTier.SILVER);
        memberCodeService.ensureCode(member.getId());
        return userRepository.findById(member.getId()).orElseThrow();
    }

    @Test
    void sellsAStudentAndAnAdultTicketAndPrintsTypeAndPricePerSeat() throws Exception {
        Cookie staff = authCookie(staffOnShift());
        Showtime st = showtimeTomorrow();
        List<Long> seats = freeSeats(st.getId(), 2);
        BigDecimal price = seat(seats.get(0)).getPrice();

        mvc.perform(get("/staff/sell/" + st.getId()).cookie(staff))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"audiencePanel\"")))
                .andExpect(content().string(containsString("HSSV · Giảm 20%")))
                .andExpect(content().string(containsString("/js/audience-tickets.js")));

        String location = mvc.perform(post("/staff/sell/" + st.getId()).with(csrf()).cookie(staff)
                        .param("showtimeSeatIds", seats.get(0).toString(), seats.get(1).toString())
                        .param("audience_" + seats.get(0), "STUDENT").param("audience_" + seats.get(1), "ADULT")
                        .param("paymentMethod", "CASH").param("amountReceived", "500000"))
                .andExpect(redirectedUrlPattern("/staff/tickets/*"))
                .andReturn().getResponse().getRedirectedUrl();
        Booking booking = sold(location);

        BigDecimal off = AudienceTicketService.discountOn(DiscountType.PERCENT, BigDecimal.valueOf(20), price);
        assertThat(off).isPositive();
        assertThat(booking.getAudienceDiscount()).isEqualByComparingTo(off);
        assertThat(booking.getTotalAmount()).isEqualByComparingTo(booking.getTicketAmount().subtract(off));
        assertThat(seat(seats.get(0)).getAudienceType()).isEqualTo(AudienceType.STUDENT);
        assertThat(seat(seats.get(1)).getAudienceType()).isNull();

        mvc.perform(get(location).cookie(staff))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ghế thường · HSSV")))
                .andExpect(content().string(containsString("Ghế thường · Người lớn")))
                .andExpect(content().string(containsString("Quý khách vui lòng kiểm tra loại vé và số tiền. Sai sót xin báo quản lý rạp.")));
    }

    @Test
    void childTicketsAreRefusedForAT16FilmAndSwitchedOffTypesCannotBeSold() throws Exception {
        Cookie staff = authCookie(staffOnShift());
        Showtime t16 = showtime(AgeRating.T16, LocalDateTime.now().plusDays(1).withHour(20).withMinute(0).withSecond(0).withNano(0));
        Long seatId = freeSeats(t16.getId(), 1).get(0);

        mvc.perform(get("/staff/sell/" + t16.getId()).cookie(staff))
                .andExpect(content().string(containsString("Trẻ em · Chỉ cho phim P, K")));
        mvc.perform(post("/staff/sell/" + t16.getId()).with(csrf()).cookie(staff)
                        .param("showtimeSeatIds", seatId.toString()).param("audience_" + seatId, "CHILD")
                        .param("paymentMethod", "CASH").param("amountReceived", "500000"))
                .andExpect(flash().attribute("error", containsString("chỉ cho phim p, k")));
        assertThat(seat(seatId).getBooking()).isNull(); // nothing sold, seat free again

        audienceTicketService.save(settings(DiscountType.PERCENT, 20, false, false));
        mvc.perform(post("/staff/sell/" + t16.getId()).with(csrf()).cookie(staff)
                        .param("showtimeSeatIds", seatId.toString()).param("audience_" + seatId, "STUDENT")
                        .param("paymentMethod", "CASH").param("amountReceived", "500000"))
                .andExpect(flash().attribute("error", containsString("đang tắt")));
        mvc.perform(post("/staff/sell/" + t16.getId()).with(csrf()).cookie(staff)
                        .param("showtimeSeatIds", seatId.toString()).param("audience_" + seatId, "NOBODY")
                        .param("paymentMethod", "CASH").param("amountReceived", "500000"))
                .andExpect(flash().attribute("error", "Loại vé không hợp lệ"));
    }

    @Test
    void holidaysFollowTheTypesSetting() throws Exception {
        Cookie staff = authCookie(staffOnShift());
        Showtime st = showtime(AgeRating.P, LocalDateTime.now().plusDays(9).withHour(19).withMinute(0).withSecond(0).withNano(0));
        holidayService.add(st.getStartTime().toLocalDate(), "Ngày lễ test " + unique());
        Long seatId = freeSeats(st.getId(), 1).get(0);

        mvc.perform(post("/staff/sell/" + st.getId()).with(csrf()).cookie(staff)
                        .param("showtimeSeatIds", seatId.toString()).param("audience_" + seatId, "STUDENT")
                        .param("paymentMethod", "CASH").param("amountReceived", "500000"))
                .andExpect(flash().attribute("error", containsString("ngày lễ")));

        audienceTicketService.save(settings(DiscountType.PERCENT, 20, true, true));
        String location = mvc.perform(post("/staff/sell/" + st.getId()).with(csrf()).cookie(staff)
                        .param("showtimeSeatIds", seatId.toString()).param("audience_" + seatId, "STUDENT")
                        .param("paymentMethod", "CASH").param("amountReceived", "500000"))
                .andExpect(redirectedUrlPattern("/staff/tickets/*"))
                .andReturn().getResponse().getRedirectedUrl();
        assertThat(sold(location).getAudienceDiscount()).isPositive();
    }

    @Test
    void aMemberGetsTheBetterOfTierDiscountAndAudienceTicketsNeverBoth() throws Exception {
        User member = silverMember();
        Cookie staff = authCookie(staffOnShift());
        Showtime st = showtimeTomorrow();
        List<Long> seats = freeSeats(st.getId(), 2);

        // Student 20% beats the silver 5%: audience tickets, no tier discount.
        String first = mvc.perform(post("/staff/sell/" + st.getId()).with(csrf()).cookie(staff)
                        .param("showtimeSeatIds", seats.get(0).toString()).param("audience_" + seats.get(0), "STUDENT")
                        .param("memberCode", member.getMemberCode()).param("memberConfirmed", "true")
                        .param("paymentMethod", "CASH").param("amountReceived", "500000"))
                .andExpect(redirectedUrlPattern("/staff/tickets/*"))
                .andReturn().getResponse().getRedirectedUrl();
        Booking audience = sold(first);
        assertThat(audience.getAudienceDiscount()).isPositive();
        assertThat(audience.getTierDiscount()).isZero();
        assertThat(audience.getMemberTier()).isEqualTo(MemberTier.SILVER);

        // Student 1,000đ is less than the silver 5%: the tier discount wins and the seat is an ordinary ticket.
        audienceTicketService.save(settings(DiscountType.AMOUNT, 1000, true, false));
        String second = mvc.perform(post("/staff/sell/" + st.getId()).with(csrf()).cookie(staff)
                        .param("showtimeSeatIds", seats.get(1).toString()).param("audience_" + seats.get(1), "STUDENT")
                        .param("memberCode", member.getMemberCode()).param("memberConfirmed", "true")
                        .param("paymentMethod", "CASH").param("amountReceived", "500000"))
                .andExpect(redirectedUrlPattern("/staff/tickets/*"))
                .andExpect(flash().attribute("success", containsString("Giảm hạng thành viên có lợi hơn")))
                .andReturn().getResponse().getRedirectedUrl();
        Booking tier = sold(second);
        assertThat(tier.getAudienceDiscount()).isZero();
        assertThat(tier.getTierDiscount()).isPositive();
        assertThat(seat(seats.get(1)).getAudienceType()).isNull();
        assertThat(tier.getTotalAmount()).isEqualByComparingTo(tier.getTicketAmount().subtract(tier.getTierDiscount()));
    }

    @Test
    void cancellingACounterTransferFreesTheSeatWithoutItsTicketType() throws Exception {
        User staffUser = staffOnShift();
        Cookie staff = authCookie(staffUser);
        Showtime st = showtimeTomorrow();
        Long seatId = freeSeats(st.getId(), 1).get(0);
        String location = mvc.perform(post("/staff/sell/" + st.getId()).with(csrf()).cookie(staff)
                        .param("showtimeSeatIds", seatId.toString()).param("audience_" + seatId, "SENIOR")
                        .param("paymentMethod", "BANK_QR"))
                .andExpect(redirectedUrlPattern("/staff/bank-transfer/*"))
                .andReturn().getResponse().getRedirectedUrl();
        assertThat(seat(seatId).getAudienceType()).isEqualTo(AudienceType.SENIOR);

        mvc.perform(post("/staff/bank-transfer/" + codeFromRedirect(location) + "/cancel").with(csrf()).cookie(staff))
                .andExpect(status().is3xxRedirection());
        ShowtimeSeat freed = seat(seatId);
        assertThat(freed.getBooking()).isNull();
        assertThat(freed.getAudienceType()).isNull();
        assertThat(freed.getAudienceDiscount()).isNull();
    }

    @Test
    void theGeneralManagerSetsTheDiscountsAndStaffCannot() throws Exception {
        Cookie admin = authCookie(account(Role.ADMIN));
        mvc.perform(get("/admin/prices").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Vé HSSV, trẻ em, người cao tuổi")))
                .andExpect(content().string(containsString("name=\"value_STUDENT\"")));

        mvc.perform(post("/admin/prices/audience").with(csrf()).cookie(admin)
                        .param("enabled_STUDENT", "on").param("type_STUDENT", "AMOUNT").param("value_STUDENT", "15.000")
                        .param("type_CHILD", "PERCENT").param("value_CHILD", "40").param("holidays_CHILD", "on")
                        .param("enabled_SENIOR", "on").param("type_SENIOR", "PERCENT").param("value_SENIOR", "25"))
                .andExpect(redirectedUrl("/admin/prices#audience"))
                .andExpect(flash().attribute("success", containsString("Đã lưu")));
        var student = audienceDiscountRepository.findByAudienceType(AudienceType.STUDENT).orElseThrow();
        assertThat(student.isEnabled()).isTrue();
        assertThat(student.getDiscountType()).isEqualTo(DiscountType.AMOUNT);
        assertThat(student.getDiscountValue()).isEqualByComparingTo("15000");
        var child = audienceDiscountRepository.findByAudienceType(AudienceType.CHILD).orElseThrow();
        assertThat(child.isEnabled()).isFalse();
        assertThat(child.isOnHolidays()).isTrue();

        mvc.perform(post("/admin/prices/audience").with(csrf()).cookie(admin)
                        .param("type_STUDENT", "PERCENT").param("value_STUDENT", "150")
                        .param("type_CHILD", "PERCENT").param("value_CHILD", "40")
                        .param("type_SENIOR", "PERCENT").param("value_SENIOR", "25"))
                .andExpect(flash().attribute("error", containsString("HSSV")));
        assertThat(audienceDiscountRepository.findByAudienceType(AudienceType.STUDENT).orElseThrow().getDiscountValue())
                .isEqualByComparingTo("15000");

        mvc.perform(post("/admin/prices/audience").with(csrf()).cookie(authCookie(staffOnShift()))
                        .param("type_STUDENT", "PERCENT").param("value_STUDENT", "99"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anExpiredCounterHoldTakenOverOnlineDoesNotPassOnItsTicketType() throws Exception {
        Cookie staff = authCookie(staffOnShift());
        Showtime st = showtimeTomorrow();
        Long seatId = freeSeats(st.getId(), 1).get(0);
        String location = mvc.perform(post("/staff/sell/" + st.getId()).with(csrf()).cookie(staff)
                        .param("showtimeSeatIds", seatId.toString()).param("audience_" + seatId, "SENIOR")
                        .param("paymentMethod", "BANK_QR"))
                .andExpect(redirectedUrlPattern("/staff/bank-transfer/*"))
                .andReturn().getResponse().getRedirectedUrl();
        // The customer walked away; the hold ran out but the clean-up job has not released the seat yet.
        ShowtimeSeat stale = seat(seatId);
        stale.setHoldExpiresAt(LocalDateTime.now().minusMinutes(1));
        showtimeSeatRepository.save(stale);
        Booking old = sold(location);
        old.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        bookingRepository.save(old);

        User member = member(30);
        Booking online = bookingService.holdSeats(member.getId(), st.getId(), List.of(seatId));

        assertThat(online.getAudienceDiscount()).isZero();
        assertThat(online.getTotalAmount()).isEqualByComparingTo(online.getTicketAmount());
        assertThat(seat(seatId).getAudienceType()).isNull();
        assertThat(seat(seatId).getAudienceDiscount()).isNull();
    }

    @Test
    void aFailedSaleGivesBackTheSeatsWithTheirTicketTypes() throws Exception {
        Cookie staff = authCookie(staffOnShift());
        Showtime st = showtimeTomorrow();
        List<Long> seats = freeSeats(st.getId(), 2);
        mvc.perform(post("/staff/sell/" + st.getId()).with(csrf()).cookie(staff)
                        .param("showtimeSeatIds", seats.get(0).toString(), seats.get(1).toString())
                        .param("audience_" + seats.get(0), "STUDENT")
                        .param("paymentMethod", "CASH").param("amountReceived", "1000"))
                .andExpect(flash().attribute("error", containsString("Khách đưa thiếu")))
                .andExpect(flash().attribute("keptSeats", seats.get(0) + ":STUDENT," + seats.get(1) + ":ADULT"));

        mvc.perform(get("/staff/sell/" + st.getId()).cookie(staff)
                        .flashAttr("keptSeats", seats.get(0) + ":STUDENT"))
                .andExpect(content().string(containsString("data-kept-seats=\"" + seats.get(0) + ":STUDENT\"")));
    }
}
