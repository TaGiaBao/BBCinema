package com.bbcinema.integration;

import com.bbcinema.entity.Booking;
import com.bbcinema.entity.CounterShift;
import com.bbcinema.entity.Showtime;
import com.bbcinema.entity.User;
import com.bbcinema.entity.enums.MemberTier;
import com.bbcinema.entity.enums.PaymentMethod;
import com.bbcinema.entity.enums.Role;
import com.bbcinema.entity.enums.SalesChannel;
import com.bbcinema.repository.PaymentRepository;
import com.bbcinema.service.BookingService;
import com.bbcinema.service.MemberCodeService;
import com.bbcinema.service.MembershipService;
import com.bbcinema.service.PointService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Member codes: given at sign-up, shown on the member page, looked up at the counter and used for counter sales. */
class MemberCodeIntegrationTest extends IntegrationTestBase {

    private static final String CODE_PATTERN = "BB[A-HJKMNP-Z2-9]{8}";

    @Autowired MemberCodeService memberCodeService;
    @Autowired MembershipService membershipService;
    @Autowired PointService pointService;
    @Autowired PaymentRepository paymentRepository;

    private String uniquePhone() {
        return String.format("0987%06d", unique());
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    /** A member with a phone number who spent 1,200,000đ this year: SILVER, 5% off. */
    private User silverMember() {
        User member = member(30);
        member.setPhone(uniquePhone());
        member = userRepository.save(member);
        Showtime st = showtimeTomorrow();
        Booking booking = bookingService.holdSeats(member.getId(), st.getId(), freeSeats(st.getId(), 1));
        booking = bookingService.payWithMock(booking.getCode(), member.getId());
        Booking stored = bookingRepository.findById(booking.getId()).orElseThrow();
        stored.setTotalAmount(BigDecimal.valueOf(1_200_000));
        bookingRepository.save(stored);
        member = reload(member);
        assertThat(membershipService.tierOf(member)).isEqualTo(MemberTier.SILVER);
        return member;
    }

    private static String masked(String code) {
        return code.substring(0, 2) + "\u2022".repeat(5) + code.substring(7);
    }

    private String codeFromRedirect(String location) {
        return location.substring(location.lastIndexOf('/') + 1);
    }

    // ------------------------------------------------------------------ codes

    @Test
    void membersGetACodeAndInternalAccountsDoNot() {
        User member = member(20);
        assertThat(member.getMemberCode()).matches(CODE_PATTERN);
        for (Role role : List.of(Role.STAFF, Role.MANAGER, Role.ADMIN)) {
            assertThat(account(role).getMemberCode()).isNull();
        }
        // Codes are random, not counters: two members in a row share no visible sequence.
        assertThat(member(20).getMemberCode()).isNotEqualTo(member.getMemberCode());
    }

    @Test
    void accountTurnedIntoAMemberGetsACode() {
        User admin = account(Role.ADMIN);
        User staff = account(Role.STAFF);
        userService.updateByAdmin(admin.getId(), staff.getId(), Role.USER, true, null);
        assertThat(reload(staff).getMemberCode()).matches(CODE_PATTERN);
    }

    @Test
    void backfillGivesCodesToOldMembersOnly() {
        User oldMember = member(40);
        oldMember.setMemberCode(null);
        userRepository.save(oldMember);
        User staff = account(Role.STAFF);

        assertThat(memberCodeService.backfillMissing()).isGreaterThanOrEqualTo(1);
        assertThat(reload(oldMember).getMemberCode()).matches(CODE_PATTERN);
        assertThat(reload(staff).getMemberCode()).isNull();
        assertThat(memberCodeService.backfillMissing()).isZero();
    }

    // ------------------------------------------------------------------ member page

    @Test
    void memberPageShowsOwnCodeAndQr() throws Exception {
        User member = member(25);
        mvc.perform(get("/member").cookie(authCookie(member)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(member.getMemberCode())))
                .andExpect(content().string(containsString("src=\"/member/qr.png\"")))
                .andExpect(content().string(containsString("Đưa mã này cho nhân viên quầy để được giá thành viên và tích điểm.")));

        byte[] png = mvc.perform(get("/member/qr.png").cookie(authCookie(member)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_PNG))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(png).startsWith(0x89, 'P', 'N', 'G');
    }

    @Test
    void memberPageInEnglish() throws Exception {
        User member = member(25);
        mvc.perform(get("/member").param("lang", "en").cookie(authCookie(member)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Show this code to the counter staff to get member prices and earn points.")));
    }

    @Test
    void oldMemberGetsCodeWhenOpeningThePage() throws Exception {
        User member = member(25);
        member.setMemberCode(null);
        userRepository.save(member);
        mvc.perform(get("/member").cookie(authCookie(member))).andExpect(status().isOk());
        assertThat(reload(member).getMemberCode()).matches(CODE_PATTERN);
    }

    @Test
    void qrIsOnlyForTheSignedInMember() throws Exception {
        mvc.perform(get("/member/qr.png")).andExpect(status().is3xxRedirection());
        // Internal accounts have no member code, so no QR.
        mvc.perform(get("/member/qr.png").cookie(authCookie(account(Role.ADMIN)))).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ counter lookup

    @Test
    void staffFindMemberByCodeWithOnlyWhatTheyNeed() throws Exception {
        User member = silverMember();
        Cookie staff = authCookie(staffOnShift());
        mvc.perform(post("/staff/members/lookup").with(csrf()).cookie(staff).param("q", member.getMemberCode().toLowerCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.matchedBy").value("CODE"))
                .andExpect(jsonPath("$.fullName").value(member.getFullName()))
                .andExpect(jsonPath("$.memberCode").value(member.getMemberCode()))
                .andExpect(jsonPath("$.tier").value("SILVER"))
                .andExpect(jsonPath("$.discountPercent").value(5))
                .andExpect(jsonPath("$.age").value(30))
                .andExpect(jsonPath("$.maskedPhone").value(member.getPhone().substring(0, 2) + "xx xxx " + member.getPhone().substring(7)))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.points").doesNotExist())
                .andExpect(content().string(not(containsString(member.getEmail()))))
                .andExpect(content().string(not(containsString(member.getPhone()))));
    }

    @Test
    void staffFindMemberByUniquePhoneButNotASharedOne() throws Exception {
        User member = silverMember();
        Cookie staff = authCookie(staffOnShift());
        mvc.perform(post("/staff/members/lookup").with(csrf()).cookie(staff).param("q", member.getPhone()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchedBy").value("PHONE"))
                .andExpect(jsonPath("$.fullName").value(member.getFullName()))
                .andExpect(jsonPath("$.memberCode").value(member.getMemberCode()));

        User twin = member(22);
        twin.setPhone(member.getPhone());
        userRepository.save(twin);
        mvc.perform(post("/staff/members/lookup").with(csrf()).cookie(staff).param("q", member.getPhone()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("mã thành viên")));
        // The member code still works.
        mvc.perform(post("/staff/members/lookup").with(csrf()).cookie(staff).param("q", member.getMemberCode()))
                .andExpect(status().isOk());
    }

    @Test
    void staffAccountsAndLockedMembersAreNotFound() throws Exception {
        Cookie staff = authCookie(staffOnShift());
        User colleague = account(Role.STAFF);
        colleague.setPhone(uniquePhone());
        userRepository.save(colleague);
        mvc.perform(post("/staff/members/lookup").with(csrf()).cookie(staff).param("q", colleague.getPhone()))
                .andExpect(status().isNotFound());

        User locked = member(30);
        locked.setPhone(uniquePhone());
        locked.setEnabled(false);
        userRepository.save(locked);
        mvc.perform(post("/staff/members/lookup").with(csrf()).cookie(staff).param("q", locked.getMemberCode()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/staff/members/lookup").with(csrf()).cookie(staff).param("q", locked.getPhone()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/staff/members/lookup").with(csrf()).cookie(staff).param("q", "xin chao"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void lookupNeedsCounterRoleAndCsrf() throws Exception {
        User member = member(30);
        mvc.perform(post("/staff/members/lookup").cookie(authCookie(staffOnShift())).param("q", member.getMemberCode()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/staff/members/lookup").with(csrf()).cookie(authCookie(member(30))).param("q", member.getMemberCode()))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ counter sale

    @Test
    void cashSaleWithMemberGivesTierDiscountPointsAndHistory() throws Exception {
        User member = silverMember();
        int pointsBefore = member.getPoints();
        User staffUser = account(Role.STAFF);
        CounterShift shift = openShift(staffUser, 500_000);
        Cookie staff = authCookie(staffUser);
        Showtime st = showtimeTomorrow();
        List<Long> seats = freeSeats(st.getId(), 2);

        mvc.perform(get("/staff/sell/" + st.getId()).cookie(staff))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Khách thành viên")))
                .andExpect(content().string(containsString("name=\"memberCode\"")))
                .andExpect(content().string(containsString("Khách đã xác nhận đúng họ tên")))
                .andExpect(content().string(not(containsString("name=\"memberId\""))))
                .andExpect(content().string(containsString("/js/member-lookup.js")));

        String location = mvc.perform(post("/staff/sell/" + st.getId()).with(csrf()).cookie(staff)
                        .param("showtimeSeatIds", seats.get(0).toString(), seats.get(1).toString())
                        .param("paymentMethod", "CASH").param("amountReceived", "500000")
                        .param("memberCode", member.getMemberCode()).param("memberConfirmed", "true"))
                .andExpect(redirectedUrlPattern("/staff/tickets/*"))
                .andReturn().getResponse().getRedirectedUrl();
        Booking sold = bookingRepository.findDetailedByCode(codeFromRedirect(location)).orElseThrow();

        assertThat(sold.getUser().getId()).isEqualTo(member.getId());
        assertThat(sold.getChannel()).isEqualTo(SalesChannel.COUNTER);
        assertThat(sold.isPaid()).isTrue();
        assertThat(sold.getMemberTier()).isEqualTo(MemberTier.SILVER);
        BigDecimal expectedDiscount = membershipService.discountOn(MemberTier.SILVER, sold.getTicketAmount());
        assertThat(expectedDiscount).isPositive();
        assertThat(sold.getTierDiscount()).isEqualByComparingTo(expectedDiscount);
        assertThat(sold.getTotalAmount()).isEqualByComparingTo(sold.getTicketAmount().subtract(expectedDiscount));
        assertThat(sold.getPointsUsed()).isZero();
        assertThat(sold.getPromotion()).isNull();

        int earned = pointService.pointsEarnedFor(sold.getTotalAmount());
        assertThat(earned).isPositive();
        assertThat(sold.getPointsEarned()).isEqualTo(earned);
        assertThat(reload(member).getPoints()).isEqualTo(pointsBefore + earned);

        // The till counts what the customer really paid (the discounted total).
        var cash = paymentRepository.findByTransactionRef("CASH-" + sold.getCode()).orElseThrow();
        assertThat(cash.getAmount()).isEqualByComparingTo(sold.getTotalAmount());
        assertThat(cash.getChangeAmount()).isEqualByComparingTo(BigDecimal.valueOf(500_000).subtract(sold.getTotalAmount()));
        assertThat(shiftService.summary(shift).netCash()).isEqualByComparingTo(sold.getTotalAmount());

        mvc.perform(get("/member/bookings").cookie(authCookie(member)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(sold.getCode())));

        mvc.perform(get(location).cookie(staff))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-role=\"ticket-member\"")))
                .andExpect(content().string(containsString(member.getFullName() + " · " + masked(member.getMemberCode()))))
                .andExpect(content().string(not(containsString(member.getMemberCode()))))
                .andExpect(content().string(containsString("Giảm hạng Bạc")));
    }

    @Test
    void cardAndBankTransferSalesWithMemberUseTheDiscountedTotal() throws Exception {
        User member = silverMember();
        User staffUser = account(Role.STAFF);
        CounterShift shift = openShift(staffUser, 0);
        Cookie staff = authCookie(staffUser);
        Showtime st = showtimeTomorrow();
        List<Long> seats = freeSeats(st.getId(), 2);
        BigDecimal price = showtimeSeatRepository.findById(seats.get(0)).orElseThrow().getPrice();
        BigDecimal discounted = price.subtract(membershipService.discountOn(MemberTier.SILVER, price));

        // The POS slip must match the member price, not the full price.
        mvc.perform(post("/staff/sell/" + st.getId()).with(csrf()).cookie(staff)
                        .param("showtimeSeatIds", seats.get(0).toString()).param("paymentMethod", "CARD")
                        .param("cardApprovalCode", "AB12CD").param("amountReceived", price.toBigInteger().toString())
                        .param("memberCode", member.getMemberCode()).param("memberConfirmed", "true"))
                .andExpect(flash().attribute("error", containsString("Hoá đơn POS thừa")));
        String card = mvc.perform(post("/staff/sell/" + st.getId()).with(csrf()).cookie(staff)
                        .param("showtimeSeatIds", seats.get(0).toString()).param("paymentMethod", "CARD")
                        .param("cardApprovalCode", "AB12CD").param("amountReceived", discounted.toBigInteger().toString())
                        .param("memberCode", member.getMemberCode()).param("memberConfirmed", "true"))
                .andExpect(redirectedUrlPattern("/staff/tickets/*"))
                .andReturn().getResponse().getRedirectedUrl();
        Booking byCard = bookingRepository.findDetailedByCode(codeFromRedirect(card)).orElseThrow();
        assertThat(byCard.getTotalAmount()).isEqualByComparingTo(discounted);
        assertThat(byCard.getPointsEarned()).isEqualTo(pointService.pointsEarnedFor(discounted));

        String transfer = mvc.perform(post("/staff/sell/" + st.getId()).with(csrf()).cookie(staff)
                        .param("showtimeSeatIds", seats.get(1).toString()).param("paymentMethod", "BANK_QR")
                        .param("memberCode", member.getMemberCode()).param("memberConfirmed", "true"))
                .andExpect(redirectedUrlPattern("/staff/bank-transfer/*"))
                .andReturn().getResponse().getRedirectedUrl();
        String code = codeFromRedirect(transfer);
        Booking pending = bookingRepository.findDetailedByCode(code).orElseThrow();
        assertThat(pending.getUser().getId()).isEqualTo(member.getId());
        assertThat(pending.getPointsEarned()).isZero(); // points only once the money is in
        int before = reload(member).getPoints();

        bookingService.confirmBankTransfer(code, pending.getTotalAmount(), "FT123");
        Booking paid = bookingRepository.findDetailedByCode(code).orElseThrow();
        assertThat(paid.isPaid()).isTrue();
        assertThat(paid.getPointsEarned()).isEqualTo(pointService.pointsEarnedFor(paid.getTotalAmount()));
        assertThat(reload(member).getPoints()).isEqualTo(before + paid.getPointsEarned());
        var summary = shiftService.summary(shift);
        assertThat(summary.cardTotal()).isEqualByComparingTo(discounted);
        assertThat(summary.transferTotal()).isEqualByComparingTo(paid.getTotalAmount());
        assertThat(paymentRepository.findByTransactionRef("QR-" + code).orElseThrow().getMethod()).isEqualTo(PaymentMethod.BANK_QR);
    }

    @Test
    void saleWithoutMemberIsUnchanged() throws Exception {
        Cookie staff = authCookie(staffOnShift());
        Showtime st = showtimeTomorrow();
        String location = mvc.perform(post("/staff/sell/" + st.getId()).with(csrf()).cookie(staff)
                        .param("showtimeSeatIds", freeSeats(st.getId(), 1).get(0).toString())
                        .param("paymentMethod", "CASH").param("amountReceived", "500000"))
                .andExpect(redirectedUrlPattern("/staff/tickets/*"))
                .andReturn().getResponse().getRedirectedUrl();
        Booking sold = bookingRepository.findDetailedByCode(codeFromRedirect(location)).orElseThrow();
        assertThat(sold.getUser()).isNull();
        assertThat(sold.getTierDiscount()).isEqualByComparingTo("0");
        assertThat(sold.getTotalAmount()).isEqualByComparingTo(sold.getTicketAmount());
        assertThat(sold.getPointsEarned()).isZero();
        mvc.perform(get(location).cookie(staff))
                .andExpect(content().string(not(containsString("data-role=\"ticket-member\""))));
    }

    @Test
    void tamperedMemberCodeIsRefusedAndNothingIsSold() throws Exception {
        Cookie staff = authCookie(staffOnShift());
        Showtime st = showtimeTomorrow();
        Long seat = freeSeats(st.getId(), 1).get(0);
        User locked = member(30);
        locked.setEnabled(false);
        userRepository.save(locked);
        User unverified = member(30);
        unverified.setEmailVerifiedAt(null);
        userRepository.save(unverified);
        // A former member moved to the staff keeps the old code in the database; it must not work any more.
        User formerMember = member(30);
        formerMember.setRole(Role.STAFF);
        userRepository.save(formerMember);
        List<String> badCodes = List.of(locked.getMemberCode(), unverified.getMemberCode(), formerMember.getMemberCode(),
                "BBNNNNNNNN", member(30).getId().toString(), "x");
        long bookingsBefore = bookingRepository.count();

        for (String code : badCodes) {
            mvc.perform(post("/staff/sell/" + st.getId()).with(csrf()).cookie(staff)
                            .param("showtimeSeatIds", seat.toString())
                            .param("paymentMethod", "CASH").param("amountReceived", "500000")
                            .param("memberCode", code).param("memberConfirmed", "true"))
                    .andExpect(redirectedUrl("/staff/sell/" + st.getId()))
                    .andExpect(flash().attribute("error", containsString("Tài khoản thành viên không hợp lệ")));
        }
        // The old id-based field is ignored: the sale goes through without a member.
        String location = mvc.perform(post("/staff/sell/" + st.getId()).with(csrf()).cookie(staff)
                        .param("showtimeSeatIds", seat.toString())
                        .param("paymentMethod", "CASH").param("amountReceived", "500000")
                        .param("memberId", member(30).getId().toString()))
                .andExpect(redirectedUrlPattern("/staff/tickets/*"))
                .andReturn().getResponse().getRedirectedUrl();
        assertThat(bookingRepository.findDetailedByCode(codeFromRedirect(location)).orElseThrow().getUser()).isNull();
        assertThat(bookingRepository.count()).isEqualTo(bookingsBefore + 1);
    }

    @Test
    void unverifiedMembersAreNotFoundAtTheCounter() throws Exception {
        User member = member(30);
        member.setPhone(uniquePhone());
        member.setEmailVerifiedAt(null);
        userRepository.save(member);
        Cookie staff = authCookie(staffOnShift());
        mvc.perform(post("/staff/members/lookup").with(csrf()).cookie(staff).param("q", member.getMemberCode()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/staff/members/lookup").with(csrf()).cookie(staff).param("q", member.getPhone()))
                .andExpect(status().isNotFound());
    }

    @Test
    void lookupGivesTheAgeOnTheShowtimeDate() throws Exception {
        User member = member(30);
        Showtime st = showtimeTomorrow();
        // Turns 16 tomorrow: 15 today, 16 on the day of the film.
        member.setBirthDate(st.getStartTime().toLocalDate().minusYears(16));
        userRepository.save(member);
        Cookie staff = authCookie(staffOnShift());
        mvc.perform(post("/staff/members/lookup").with(csrf()).cookie(staff).param("q", member.getMemberCode()))
                .andExpect(jsonPath("$.age").value(15));
        mvc.perform(post("/staff/members/lookup").with(csrf()).cookie(staff).param("q", member.getMemberCode())
                        .param("showtimeId", st.getId().toString()))
                .andExpect(jsonPath("$.age").value(16));
    }

    @Test
    void memberFoundByPhoneNeedsTheNameConfirmed() throws Exception {
        User member = silverMember();
        Cookie staff = authCookie(staffOnShift());
        Showtime st = showtimeTomorrow();
        Long seat = freeSeats(st.getId(), 1).get(0);
        long bookingsBefore = bookingRepository.count();

        mvc.perform(post("/staff/sell/" + st.getId()).with(csrf()).cookie(staff)
                        .param("showtimeSeatIds", seat.toString())
                        .param("paymentMethod", "CASH").param("amountReceived", "500000")
                        .param("memberCode", member.getMemberCode()).param("memberMatchedBy", "PHONE"))
                .andExpect(redirectedUrl("/staff/sell/" + st.getId()))
                .andExpect(flash().attribute("error", containsString("xác nhận họ tên")))
                // The member stays attached for the retry, so the customer is not charged full price by mistake.
                .andExpect(flash().attribute("keptMemberCode", member.getMemberCode()))
                .andExpect(flash().attribute("keptMemberConfirmed", false))
                .andExpect(flash().attribute("keptMemberMatchedBy", "PHONE"));
        assertThat(bookingRepository.count()).isEqualTo(bookingsBefore);

        mvc.perform(post("/staff/sell/" + st.getId()).with(csrf()).cookie(staff)
                        .param("showtimeSeatIds", seat.toString())
                        .param("paymentMethod", "CASH").param("amountReceived", "500000")
                        .param("memberCode", member.getMemberCode()).param("memberMatchedBy", "PHONE")
                        .param("memberConfirmed", "true"))
                .andExpect(redirectedUrlPattern("/staff/tickets/*"));
    }

    @Test
    void failedSaleKeepsTheAttachedMemberOnThePage() throws Exception {
        User member = silverMember();
        Cookie staff = authCookie(staffOnShift());
        Showtime st = showtimeTomorrow();
        Long seat = freeSeats(st.getId(), 1).get(0);
        // Not enough cash: the sale fails, the member stays attached.
        var flash = mvc.perform(post("/staff/sell/" + st.getId()).with(csrf()).cookie(staff)
                        .param("showtimeSeatIds", seat.toString())
                        .param("paymentMethod", "CASH").param("amountReceived", "1000")
                        .param("memberCode", member.getMemberCode()).param("memberConfirmed", "true"))
                .andExpect(redirectedUrl("/staff/sell/" + st.getId()))
                .andExpect(flash().attribute("keptMemberCode", member.getMemberCode()))
                .andExpect(flash().attribute("keptMemberConfirmed", true))
                .andExpect(flash().attribute("keptMemberMatchedBy", "CODE"))
                .andReturn().getFlashMap();
        var page = get("/staff/sell/" + st.getId()).cookie(staff);
        flash.forEach(page::flashAttr);
        mvc.perform(page)
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-kept-code=\"" + member.getMemberCode() + "\"")))
                .andExpect(content().string(containsString("data-kept-confirmed=\"true\"")));
    }

    // ------------------------------------------------------------------ counter tickets are view-only online

    /** A ticket sold at the counter to the member, paid by transfer later (still pending) or in cash. */
    private Booking counterSale(User member, String method) throws Exception {
        User staffUser = account(Role.STAFF);
        openShift(staffUser, 500_000);
        Showtime st = showtimeTomorrow();
        var request = post("/staff/sell/" + st.getId()).with(csrf()).cookie(authCookie(staffUser))
                .param("showtimeSeatIds", freeSeats(st.getId(), 1).get(0).toString())
                .param("paymentMethod", method)
                .param("memberCode", member.getMemberCode()).param("memberConfirmed", "true");
        if ("CASH".equals(method)) {
            request.param("amountReceived", "500000");
        }
        String location = mvc.perform(request).andReturn().getResponse().getRedirectedUrl();
        Booking booking = bookingRepository.findDetailedByCode(codeFromRedirect(location)).orElseThrow();
        assertThat(booking.getChannel()).isEqualTo(SalesChannel.COUNTER);
        assertThat(booking.getUser().getId()).isEqualTo(member.getId());
        return booking;
    }

    @Test
    void memberCannotChangeOrCancelACounterTicketOnTheWeb() throws Exception {
        User member = silverMember();
        Cookie me = authCookie(member);
        Booking paid = counterSale(member, "CASH");
        Booking pending = counterSale(member, "BANK_QR");
        String base = "/booking/" + pending.getCode();

        // Viewing is fine: ticket page (without the cancel button), QR, payment status, history.
        mvc.perform(get("/booking/" + paid.getCode()).cookie(me))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("/booking/" + paid.getCode() + "/cancel"))));
        mvc.perform(get("/booking/" + paid.getCode() + "/qr.png").cookie(me)).andExpect(status().isOk());
        mvc.perform(get(base + "/payment-status").cookie(me)).andExpect(status().isOk());
        mvc.perform(get("/member/bookings").cookie(me))
                .andExpect(content().string(containsString(paid.getCode())));

        mvc.perform(post("/booking/" + paid.getCode() + "/cancel").with(csrf()).cookie(me))
                .andExpect(redirectedUrl("/booking/" + paid.getCode()))
                .andExpect(flash().attribute("error", BookingService.COUNTER_ONLY));
        for (String path : List.of("/combos", "/promotion", "/promotion/remove", "/points", "/pay")) {
            var request = post(base + path).with(csrf()).cookie(me);
            if (path.equals("/promotion")) {
                request.param("promotionCode", "BBCINEMA10");
            }
            if (path.equals("/points")) {
                request.param("points", "1");
            }
            mvc.perform(request).andExpect(flash().attribute("error", BookingService.COUNTER_ONLY));
        }
        for (String path : List.of("/combos", "/checkout", "/bank-transfer")) {
            mvc.perform(get(base + path).cookie(me))
                    .andExpect(redirectedUrl(base))
                    .andExpect(flash().attribute("error", BookingService.COUNTER_ONLY));
        }
        mvc.perform(get(base + "/bank-qr.png").cookie(me)).andExpect(status().is4xxClientError());

        Booking paidAfter = bookingRepository.findDetailedByCode(paid.getCode()).orElseThrow();
        Booking pendingAfter = bookingRepository.findDetailedByCode(pending.getCode()).orElseThrow();
        assertThat(paidAfter.isPaid()).isTrue();
        assertThat(pendingAfter.isPending()).isTrue();
        assertThat(pendingAfter.getPointsUsed()).isZero();
        assertThat(pendingAfter.getPromotion()).isNull();
        assertThat(pendingAfter.getTotalAmount()).isEqualByComparingTo(pending.getTotalAmount());
    }

    @Test
    void memberCannotChangeOrCancelACounterTicketThroughTheApi() throws Exception {
        User member = silverMember();
        String auth = bearer(member);
        Booking paid = counterSale(member, "CASH");
        Booking pending = counterSale(member, "BANK_QR");
        String base = "/api/bookings/" + pending.getCode();

        mvc.perform(get("/api/bookings/" + paid.getCode()).header("Authorization", auth)).andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/bookings/" + paid.getCode())
                        .header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(BookingService.COUNTER_ONLY));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(base + "/combos")
                        .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content("{\"quantities\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(BookingService.COUNTER_ONLY));
        mvc.perform(post(base + "/promotion").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"BBCINEMA10\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(BookingService.COUNTER_ONLY));
        mvc.perform(post(base + "/points").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"points\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(BookingService.COUNTER_ONLY));
        mvc.perform(post(base + "/pay").header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(BookingService.COUNTER_ONLY));

        assertThat(bookingRepository.findDetailedByCode(paid.getCode()).orElseThrow().isPaid()).isTrue();
        assertThat(bookingRepository.findDetailedByCode(pending.getCode()).orElseThrow().isPending()).isTrue();
    }

    @Test
    void formerMemberNowStaffHasNoQrOrCodeCard() throws Exception {
        User member = member(30);
        String oldCode = member.getMemberCode();
        member.setRole(Role.STAFF);
        userRepository.save(member);
        mvc.perform(get("/member/qr.png").cookie(authCookie(member))).andExpect(status().isNotFound());
        assertThat(memberCodeService.ensureCode(member.getId())).isNull();
        assertThat(oldCode).isNotNull();
    }
}
