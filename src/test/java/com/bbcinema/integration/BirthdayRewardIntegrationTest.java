package com.bbcinema.integration;

import com.bbcinema.dto.request.BirthdayRewardForm;
import com.bbcinema.entity.*;
import com.bbcinema.entity.enums.DiscountType;
import com.bbcinema.entity.enums.Role;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.repository.BirthdayRewardRepository;
import com.bbcinema.repository.BirthdayRewardSettingsRepository;
import com.bbcinema.service.BirthdayRewardService;
import com.bbcinema.service.PromotionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Birthday gift codes: issuing (on fixed dates), the owner-only rule at checkout, the admin and member pages. */
class BirthdayRewardIntegrationTest extends IntegrationTestBase {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired BirthdayRewardService birthdayRewardService;
    @Autowired BirthdayRewardRepository rewardRepository;
    @Autowired BirthdayRewardSettingsRepository settingsRepository;
    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired PlatformTransactionManager transactionManager;

    @AfterEach
    void turnOff() {
        BirthdayRewardForm off = settingsForm(false, DiscountType.PERCENT, 20, 50_000L, null);
        birthdayRewardService.saveSettings(off);
    }

    /** The service as it would run on {@code day} at 08:00. */
    private BirthdayRewardService on(LocalDate day) {
        Clock clock = Clock.fixed(day.atTime(8, 0).atZone(ZONE).toInstant(), ZONE);
        return new BirthdayRewardService(settingsRepository, rewardRepository, promotionRepository, userRepository,
                eventPublisher, clock, transactionManager);
    }

    private static BirthdayRewardForm settingsForm(boolean enabled, DiscountType type, long value, Long max, Long minOrder) {
        BirthdayRewardForm form = new BirthdayRewardForm();
        form.setEnabled(enabled);
        form.setDiscountType(type);
        form.setDiscountValue(BigDecimal.valueOf(value));
        form.setMaxDiscount(max == null ? null : BigDecimal.valueOf(max));
        form.setMinOrder(minOrder == null ? null : BigDecimal.valueOf(minOrder));
        return form;
    }

    private void enable() {
        birthdayRewardService.saveSettings(settingsForm(true, DiscountType.PERCENT, 20, 50_000L, null));
    }

    private User memberBorn(LocalDate birthDate) {
        int n = unique();
        User user = userService.createAccount("bday" + n + "@test.vn", "Sinh nhật " + n, "Secret@123", Role.USER);
        user.setBirthDate(birthDate);
        return userRepository.save(user);
    }

    private Promotion personalCodeOf(User user) {
        return rewardRepository.findAll().stream()
                .filter(r -> r.getUser().getId().equals(user.getId()))
                .map(BirthdayReward::getPromotion)
                .map(p -> promotionRepository.findById(p.getId()).orElseThrow())
                .findFirst().orElseThrow();
    }

    private long ownedCodes(User user) {
        return promotionRepository.findAll().stream()
                .filter(p -> p.getOwner() != null && p.getOwner().getId().equals(user.getId()))
                .count();
    }

    // ------------------------------------------------------------------ issuing

    @Test
    void givesAPersonalSingleUseCodeOnTheFirstDayOfTheBirthdayMonth() {
        enable();
        User member = memberBorn(LocalDate.of(1995, 3, 18));

        assertThat(on(LocalDate.of(2031, 3, 1)).issueDue()).isPositive();

        BirthdayReward reward = rewardRepository.findForMember(member.getId(), 2031).orElseThrow();
        Promotion code = reward.getPromotion();
        assertThat(code.getCode()).matches("SN[ABCDEFGHJKMNPQRSTUVWXYZ2-9]{8}");
        assertThat(code.getOwner().getId()).isEqualTo(member.getId());
        assertThat(code.getUsageLimit()).isEqualTo(1);
        assertThat(code.getDiscountPercent()).isEqualTo(20);
        assertThat(code.getMaxDiscount()).isEqualByComparingTo("50000");
        assertThat(code.getDiscountAmount()).isNull();
        assertThat(code.getValidFrom()).isEqualTo(LocalDate.of(2031, 3, 1));
        assertThat(code.getValidTo()).isEqualTo(LocalDate.of(2031, 3, 31));
        assertThat(code.isActive()).isTrue();
    }

    @Test
    void catchesUpLaterInTheMonthButNotForOtherMonths() {
        enable();
        User june = memberBorn(LocalDate.of(1990, 6, 2));
        User july = memberBorn(LocalDate.of(1990, 7, 1));

        on(LocalDate.of(2031, 6, 17)).issueDue();

        assertThat(rewardRepository.existsByUserIdAndRewardYear(june.getId(), 2031)).isTrue();
        assertThat(personalCodeOf(june).getValidFrom()).isEqualTo(LocalDate.of(2031, 6, 1));
        assertThat(rewardRepository.existsByUserIdAndRewardYear(july.getId(), 2031)).isFalse();
    }

    @Test
    void february29BirthdayGetsAFebruaryCodeInANonLeapYear() {
        enable();
        User leapling = memberBorn(LocalDate.of(2004, 2, 29));

        on(LocalDate.of(2031, 2, 28)).issueDue();

        Promotion code = personalCodeOf(leapling);
        assertThat(code.getValidFrom()).isEqualTo(LocalDate.of(2031, 2, 1));
        assertThat(code.getValidTo()).isEqualTo(LocalDate.of(2031, 2, 28));
    }

    @Test
    void runningAgainOrChangingTheBirthDateGivesNoSecondGiftThatYear() {
        enable();
        User member = memberBorn(LocalDate.of(1992, 4, 5));
        on(LocalDate.of(2032, 4, 5)).issueDue();
        on(LocalDate.of(2032, 4, 6)).issueDue();
        assertThat(rewardRepository.countByUserId(member.getId())).isEqualTo(1);

        member = userRepository.findById(member.getId()).orElseThrow();
        member.setBirthDate(LocalDate.of(1992, 8, 20));
        userRepository.save(member);
        on(LocalDate.of(2032, 8, 1)).issueDue();
        assertThat(rewardRepository.countByUserId(member.getId())).isEqualTo(1);
        assertThat(ownedCodes(member)).isEqualTo(1);

        // A new year is a new gift.
        on(LocalDate.of(2033, 8, 1)).issueDue();
        assertThat(rewardRepository.existsByUserIdAndRewardYear(member.getId(), 2033)).isTrue();
        assertThat(rewardRepository.countByUserId(member.getId())).isEqualTo(2);
    }

    @Test
    void twoRunsAtTheSameTimeGiveOneCode() throws Exception {
        enable();
        User member = memberBorn(LocalDate.of(1993, 5, 9));
        LocalDate day = LocalDate.of(2034, 5, 10);
        BirthdayRewardService first = on(day);
        BirthdayRewardService second = on(day);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> runs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            BirthdayRewardService service = i % 2 == 0 ? first : second;
            runs.add(pool.submit(() -> {
                start.await();
                return service.issueDue();
            }));
        }
        start.countDown();
        for (Future<Integer> run : runs) {
            run.get(30, TimeUnit.SECONDS); // no run fails because another one won
        }
        pool.shutdown();

        assertThat(rewardRepository.countByUserId(member.getId())).isEqualTo(1);
        assertThat(ownedCodes(member)).as("the losing runs leave no orphan code").isEqualTo(1);
    }

    @Test
    void noCodeForUnconfirmedMembersMembersWithoutBirthDateDisabledAccountsOrStaff() {
        enable();
        LocalDate birth = LocalDate.of(1991, 9, 9);
        User unconfirmed = memberBorn(birth);
        unconfirmed.setEmailVerifiedAt(null);
        userRepository.save(unconfirmed);
        User noBirthDate = memberBorn(birth);
        noBirthDate.setBirthDate(null);
        userRepository.save(noBirthDate);
        User disabled = memberBorn(birth);
        disabled.setEnabled(false);
        userRepository.save(disabled);
        List<User> internal = new ArrayList<>();
        for (Role role : new Role[]{Role.STAFF, Role.MANAGER, Role.ADMIN}) {
            User account = account(role);
            account.setBirthDate(birth);
            internal.add(userRepository.save(account));
        }
        User member = memberBorn(birth);

        on(LocalDate.of(2031, 9, 2)).issueDue();

        assertThat(rewardRepository.existsByUserIdAndRewardYear(member.getId(), 2031)).isTrue();
        for (User user : List.of(unconfirmed, noBirthDate, disabled)) {
            assertThat(rewardRepository.existsByUserIdAndRewardYear(user.getId(), 2031)).as(user.getEmail()).isFalse();
        }
        for (User user : internal) {
            assertThat(rewardRepository.existsByUserIdAndRewardYear(user.getId(), 2031)).as(user.getRole().name()).isFalse();
        }
    }

    @Test
    void turnedOffGivesNothingButKeepsCodesAlreadyGiven() {
        enable();
        User early = memberBorn(LocalDate.of(1994, 10, 1));
        on(LocalDate.of(2031, 10, 1)).issueDue();
        turnOff();
        User late = memberBorn(LocalDate.of(1994, 10, 25));

        assertThat(on(LocalDate.of(2031, 10, 2)).issueDue()).isZero();

        assertThat(rewardRepository.existsByUserIdAndRewardYear(late.getId(), 2031)).isFalse();
        Promotion kept = personalCodeOf(early);
        assertThat(kept.isActive()).isTrue();
        assertThat(kept.getValidTo()).isEqualTo(LocalDate.of(2031, 10, 31));
    }

    @Test
    void aFixedAmountGiftGivesAFixedAmountCodeWithTheMinimumOrder() {
        birthdayRewardService.saveSettings(settingsForm(true, DiscountType.AMOUNT, 40_000, 99_000L, 100_000L));
        BirthdayRewardSettings saved = birthdayRewardService.settings();
        assertThat(saved.getMaxDiscount()).as("a cap means nothing for a fixed amount").isNull();
        User member = memberBorn(LocalDate.of(1996, 12, 24));

        on(LocalDate.of(2031, 12, 1)).issueDue();

        Promotion code = personalCodeOf(member);
        assertThat(code.getDiscountAmount()).isEqualByComparingTo("40000");
        assertThat(code.getDiscountPercent()).isZero();
        assertThat(code.getMinOrder()).isEqualByComparingTo("100000");
        assertThat(PromotionService.discountFor(code, BigDecimal.valueOf(120_000))).isEqualByComparingTo("40000");
    }

    // ------------------------------------------------------------------ using the code

    /** A member born this month, with this year's code given today (real clock). */
    private User memberWithCodeNow() {
        enable();
        LocalDate today = LocalDate.now(ZONE);
        User member = memberBorn(today.withYear(1998).withDayOfMonth(1));
        birthdayRewardService.issueDue();
        assertThat(rewardRepository.existsByUserIdAndRewardYear(member.getId(), today.getYear())).isTrue();
        return member;
    }

    @Test
    void onlyTheOwnerCanUseTheCodeOnTheWebAndTheApi() throws Exception {
        User owner = memberWithCodeNow();
        String code = personalCodeOf(owner).getCode();
        User other = member(30);
        Showtime st = showtimeTomorrow();
        Booking othersBooking = bookingService.holdSeats(other.getId(), st.getId(), freeSeats(st.getId(), 1));

        mvc.perform(post("/booking/" + othersBooking.getCode() + "/promotion").with(csrf())
                        .cookie(authCookie(other)).param("promotionCode", code))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", PromotionService.NOT_YOUR_CODE));
        mvc.perform(post("/api/bookings/" + othersBooking.getCode() + "/promotion")
                        .header("Authorization", bearer(other))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(PromotionService.NOT_YOUR_CODE));
        assertThat(bookingRepository.findById(othersBooking.getId()).orElseThrow().getPromotion()).isNull();

        Booking ownBooking = bookingService.holdSeats(owner.getId(), st.getId(), freeSeats(st.getId(), 1));
        mvc.perform(post("/api/bookings/" + ownBooking.getCode() + "/promotion")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code.toLowerCase() + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/booking/" + ownBooking.getCode() + "/promotion").with(csrf())
                        .cookie(authCookie(owner)).param("promotionCode", code))
                .andExpect(flash().attribute("success", "Đã áp dụng mã " + code));
    }

    @Test
    void theCodeWorksOnceAndCancellingGivesTheUseBack() {
        User owner = memberWithCodeNow();
        Promotion promotion = personalCodeOf(owner);
        Showtime st = showtimeTomorrow();

        Booking first = bookingService.holdSeats(owner.getId(), st.getId(), freeSeats(st.getId(), 1));
        first = bookingService.applyPromotion(first.getCode(), owner.getId(), promotion.getCode());
        assertThat(first.getPromotionDiscount()).isPositive();
        bookingService.payWithMock(first.getCode(), owner.getId());
        assertThat(promotionRepository.findById(promotion.getId()).orElseThrow().getUsedCount()).isEqualTo(1);

        Booking second = bookingService.holdSeats(owner.getId(), st.getId(), freeSeats(st.getId(), 1));
        assertThatThrownBy(() -> bookingService.applyPromotion(second.getCode(), owner.getId(), promotion.getCode()))
                .isInstanceOf(BusinessException.class).hasMessage("Mã giảm giá đã hết lượt sử dụng");

        // Existing promotion rule: cancelling a paid booking gives the use back, so the gift can be used again.
        bookingService.cancel(first.getCode(), owner.getId(), Role.USER);
        assertThat(promotionRepository.findById(promotion.getId()).orElseThrow().getUsedCount()).isZero();
        assertThat(bookingService.applyPromotion(second.getCode(), owner.getId(), promotion.getCode()).getPromotion())
                .isNotNull();
    }

    @Test
    void theCodeIsRefusedOutsideItsBirthdayMonth() {
        enable();
        LocalDate nextMonth = LocalDate.now(ZONE).plusMonths(1).withDayOfMonth(1);
        User member = memberBorn(nextMonth.withYear(1997));
        on(nextMonth).issueDue();
        String code = personalCodeOf(member).getCode();
        Showtime st = showtimeTomorrow();
        Booking booking = bookingService.holdSeats(member.getId(), st.getId(), freeSeats(st.getId(), 1));

        assertThatThrownBy(() -> bookingService.applyPromotion(booking.getCode(), member.getId(), code))
                .hasMessage("Mã giảm giá không trong thời gian áp dụng");
    }

    // ------------------------------------------------------------------ pages

    @Test
    void theMemberSeesTheirCodeInVietnameseAndEnglish() throws Exception {
        User owner = memberWithCodeNow();
        String code = personalCodeOf(owner).getCode();

        mvc.perform(get("/member/points").cookie(authCookie(owner)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ưu đãi sinh nhật của bạn")))
                .andExpect(content().string(containsString(code)))
                .andExpect(content().string(containsString("Giảm 20%, tối đa 50.000đ")))
                .andExpect(content().string(containsString("Chưa dùng")));
        mvc.perform(get("/member/points").cookie(authCookie(owner), new Cookie("BB_LANG", "en")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Your birthday gift")))
                .andExpect(content().string(containsString("20% off, up to 50.000đ")))
                .andExpect(content().string(containsString("Not used yet")));

        User other = member(30);
        mvc.perform(get("/member/points").cookie(authCookie(other)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(code))))
                .andExpect(content().string(not(containsString("Ưu đãi sinh nhật của bạn"))));
    }

    @Test
    void onlyTheGeneralManagerOpensTheSettings() throws Exception {
        mvc.perform(get("/admin/promotions/birthday").cookie(authCookie(account(Role.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Quà sinh nhật thành viên")));
        for (Role role : new Role[]{Role.MANAGER, Role.STAFF, Role.USER}) {
            mvc.perform(get("/admin/promotions/birthday").cookie(authCookie(account(role))))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/admin/promotions/birthday").with(csrf()).cookie(authCookie(account(role)))
                            .param("enabled", "true").param("discountType", "PERCENT").param("discountValue", "90"))
                    .andExpect(status().isForbidden());
        }
        assertThat(birthdayRewardService.settings().isEnabled()).isFalse();
        mvc.perform(get("/admin/promotions/birthday")).andExpect(status().is3xxRedirection());
    }

    @Test
    void theSettingsFormChecksItsValuesAndSaves() throws Exception {
        Cookie admin = authCookie(account(Role.ADMIN));
        mvc.perform(post("/admin/promotions/birthday").with(csrf()).cookie(admin)
                        .param("enabled", "true").param("discountType", "PERCENT").param("discountValue", "150"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Giảm theo phần trăm chỉ từ 1% đến 100%")));
        mvc.perform(post("/admin/promotions/birthday").with(csrf()).cookie(admin)
                        .param("enabled", "true").param("discountType", "PERCENT").param("discountValue", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Vui lòng nhập mức giảm")));
        mvc.perform(post("/admin/promotions/birthday").with(csrf()).cookie(admin)
                        .param("enabled", "true").param("discountType", "AMOUNT").param("discountValue", "30000")
                        .param("minOrder", "-5"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Đơn tối thiểu không được âm")));
        assertThat(birthdayRewardService.settings().isEnabled()).isFalse();

        mvc.perform(post("/admin/promotions/birthday").with(csrf()).cookie(admin)
                        .param("enabled", "true").param("discountType", "AMOUNT").param("discountValue", "30000")
                        .param("minOrder", "80000"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("success", containsString("Đã lưu cài đặt quà sinh nhật")));
        BirthdayRewardSettings settings = birthdayRewardService.settings();
        assertThat(settings.isEnabled()).isTrue();
        assertThat(settings.getDiscountType()).isEqualTo(DiscountType.AMOUNT);
        assertThat(settings.getDiscountValue()).isEqualByComparingTo("30000");
        assertThat(settings.getMinOrder()).isEqualByComparingTo("80000");
    }

    @Test
    void theAdminPageListsThisYearsCodesAndTheGeneralListLeavesThemOut() throws Exception {
        User owner = memberWithCodeNow();
        Promotion code = personalCodeOf(owner);
        Cookie admin = authCookie(account(Role.ADMIN));

        mvc.perform(get("/admin/promotions/birthday").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(code.getCode())))
                .andExpect(content().string(containsString(owner.getEmail())));
        mvc.perform(get("/admin/promotions").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(code.getCode()))))
                .andExpect(content().string(containsString("/admin/promotions/birthday")));
        // A personal code cannot be turned into a general one through the promotion form.
        mvc.perform(post("/admin/promotions").with(csrf()).cookie(admin)
                        .param("id", code.getId().toString()).param("code", code.getCode())
                        .param("discountPercent", "90").param("validFrom", "2020-01-01").param("validTo", "2099-12-31")
                        .param("active", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ưu đãi cá nhân")));
        assertThat(promotionRepository.findById(code.getId()).orElseThrow().getDiscountPercent()).isEqualTo(20);
    }

    // ------------------------------------------------------------------ birth date

    @Test
    void memberCanFillInAMissingBirthDateOnceButNotMoveItLater() throws Exception {
        User member = memberBorn(null);
        mvc.perform(post("/member/profile").with(csrf()).cookie(authCookie(member))
                        .param("fullName", "Thành viên").param("birthDate", "1999-07-04"))
                .andExpect(redirectedUrl("/member"));
        assertThat(userRepository.findById(member.getId()).orElseThrow().getBirthDate()).isEqualTo(LocalDate.of(1999, 7, 4));

        // Moving it to this month would give the birthday gift on demand (and get around the age rating).
        mvc.perform(post("/member/profile").with(csrf()).cookie(authCookie(member))
                        .param("fullName", "Tên mới").param("birthDate", "1999-01-04"))
                .andExpect(redirectedUrl("/member"));
        User saved = userRepository.findById(member.getId()).orElseThrow();
        assertThat(saved.getBirthDate()).isEqualTo(LocalDate.of(1999, 7, 4));
        assertThat(saved.getFullName()).isEqualTo("Tên mới");

        mvc.perform(get("/member").cookie(authCookie(member)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("liên hệ rạp")));
    }
}
