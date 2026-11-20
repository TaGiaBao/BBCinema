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
                .andExpect(jsonPath("$.id").value(member.getId()))
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
                .andExpect(jsonPath("$.id").value(member.getId()));

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
                .andExpect(content().string(containsString("name=\"memberId\"")))
                .andExpect(content().string(containsString("/js/member-lookup.js")));

        String location = mvc.perform(post("/staff/sell/" + st.getId()).with(csrf()).cookie(staff)
                        .param("showtimeSeatIds", seats.get(0).toString(), seats.get(1).toString())
                        .param("paymentMethod", "CASH").param("amountReceived", "500000")
                        .param("memberId", member.getId().toString()))
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
                .andExpect(content().string(containsString(member.getFullName() + " · " + member.getMemberCode())))
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
                        .param("memberId", member.getId().toString()))
                .andExpect(flash().attribute("error", containsString("Hoá đơn POS thừa")));
        String card = mvc.perform(post("/staff/sell/" + st.getId()).with(csrf()).cookie(staff)
                        .param("showtimeSeatIds", seats.get(0).toString()).param("paymentMethod", "CARD")
                        .param("cardApprovalCode", "AB12CD").param("amountReceived", discounted.toBigInteger().toString())
                        .param("memberId", member.getId().toString()))
                .andExpect(redirectedUrlPattern("/staff/tickets/*"))
                .andReturn().getResponse().getRedirectedUrl();
        Booking byCard = bookingRepository.findDetailedByCode(codeFromRedirect(card)).orElseThrow();
        assertThat(byCard.getTotalAmount()).isEqualByComparingTo(discounted);
        assertThat(byCard.getPointsEarned()).isEqualTo(pointService.pointsEarnedFor(discounted));

        String transfer = mvc.perform(post("/staff/sell/" + st.getId()).with(csrf()).cookie(staff)
                        .param("showtimeSeatIds", seats.get(1).toString()).param("paymentMethod", "BANK_QR")
                        .param("memberId", member.getId().toString()))
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
    void tamperedMemberIdIsRefusedAndNothingIsSold() throws Exception {
        Cookie staff = authCookie(staffOnShift());
        Showtime st = showtimeTomorrow();
        Long seat = freeSeats(st.getId(), 1).get(0);
        User locked = member(30);
        locked.setEnabled(false);
        userRepository.save(locked);
        List<String> badIds = List.of(account(Role.STAFF).getId().toString(), account(Role.ADMIN).getId().toString(),
                account(Role.MANAGER).getId().toString(), locked.getId().toString(), "999999999");
        long bookingsBefore = bookingRepository.count();

        for (String id : badIds) {
            mvc.perform(post("/staff/sell/" + st.getId()).with(csrf()).cookie(staff)
                            .param("showtimeSeatIds", seat.toString())
                            .param("paymentMethod", "CASH").param("amountReceived", "500000")
                            .param("memberId", id))
                    .andExpect(redirectedUrl("/staff/sell/" + st.getId()))
                    .andExpect(flash().attribute("error", containsString("Tài khoản thành viên không hợp lệ")));
        }
        assertThat(bookingRepository.count()).isEqualTo(bookingsBefore);
        assertThat(freeSeats(st.getId(), 100)).contains(seat);
    }
}
