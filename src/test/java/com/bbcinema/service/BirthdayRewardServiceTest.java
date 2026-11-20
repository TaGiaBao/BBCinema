package com.bbcinema.service;

import com.bbcinema.dto.request.BirthdayRewardForm;
import com.bbcinema.entity.Promotion;
import com.bbcinema.entity.User;
import com.bbcinema.entity.enums.DiscountType;
import com.bbcinema.entity.enums.Role;
import com.bbcinema.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BirthdayRewardServiceTest {

    @Test
    void windowIsTheWholeBirthdayMonthOfThatYear() {
        LocalDate birth = LocalDate.of(1999, 11, 20);
        assertThat(BirthdayRewardService.windowStart(birth, 2026)).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(BirthdayRewardService.windowEnd(birth, 2026)).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(BirthdayRewardService.inWindow(birth, LocalDate.of(2026, 11, 1))).isTrue();
        assertThat(BirthdayRewardService.inWindow(birth, LocalDate.of(2026, 11, 30))).isTrue();
        assertThat(BirthdayRewardService.inWindow(birth, LocalDate.of(2026, 10, 31))).isFalse();
        assertThat(BirthdayRewardService.inWindow(birth, LocalDate.of(2026, 12, 1))).isFalse();
        assertThat(BirthdayRewardService.inWindow(null, LocalDate.of(2026, 11, 5))).isFalse();
    }

    @Test
    void february29BirthdaysGetFebruaryInEveryYear() {
        LocalDate birth = LocalDate.of(2004, 2, 29);
        assertThat(BirthdayRewardService.windowStart(birth, 2027)).isEqualTo(LocalDate.of(2027, 2, 1));
        assertThat(BirthdayRewardService.windowEnd(birth, 2027)).isEqualTo(LocalDate.of(2027, 2, 28));
        assertThat(BirthdayRewardService.windowEnd(birth, 2028)).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThat(BirthdayRewardService.inWindow(birth, LocalDate.of(2027, 2, 28))).isTrue();
        assertThat(BirthdayRewardService.inWindow(birth, LocalDate.of(2027, 3, 1))).isFalse();
    }

    @Test
    void onlyEnabledConfirmedMembersWithABirthDateAreEligible() {
        assertThat(BirthdayRewardService.isEligible(user(Role.USER, true, true, true))).isTrue();
        assertThat(BirthdayRewardService.isEligible(user(Role.USER, false, true, true))).as("unconfirmed").isFalse();
        assertThat(BirthdayRewardService.isEligible(user(Role.USER, true, false, true))).as("no birth date").isFalse();
        assertThat(BirthdayRewardService.isEligible(user(Role.USER, true, true, false))).as("disabled").isFalse();
        for (Role role : new Role[]{Role.STAFF, Role.MANAGER, Role.ADMIN}) {
            assertThat(BirthdayRewardService.isEligible(user(role, true, true, true))).as(role.name()).isFalse();
        }
    }

    @Test
    void settingsValidationGivesVietnameseMessages() {
        assertThatThrownBy(() -> BirthdayRewardService.validate(form(DiscountType.PERCENT, 101)))
                .isInstanceOf(BusinessException.class).hasMessage("Giảm theo phần trăm chỉ từ 1% đến 100%");
        assertThatThrownBy(() -> BirthdayRewardService.validate(form(DiscountType.AMOUNT, 500)))
                .hasMessage("Số tiền giảm tối thiểu là 1.000đ");
        assertThatThrownBy(() -> BirthdayRewardService.validate(form(DiscountType.PERCENT, 0)))
                .hasMessage("Mức giảm phải lớn hơn 0");
        assertThatThrownBy(() -> BirthdayRewardService.validate(form(null, 10)))
                .hasMessage("Vui lòng chọn cách giảm giá và nhập mức giảm");
        assertThatCode(() -> BirthdayRewardService.validate(form(DiscountType.PERCENT, 100))).doesNotThrowAnyException();
        assertThatCode(() -> BirthdayRewardService.validate(form(DiscountType.AMOUNT, 50_000))).doesNotThrowAnyException();
    }

    @Test
    void describesTheGiftInWords() {
        Promotion percent = new Promotion();
        percent.setDiscountPercent(20);
        assertThat(BirthdayRewardService.describe(percent)).isEqualTo("Giảm 20%");
        percent.setMaxDiscount(BigDecimal.valueOf(50_000));
        assertThat(BirthdayRewardService.describe(percent)).isEqualTo("Giảm 20%, tối đa 50.000đ");
        Promotion amount = new Promotion();
        amount.setDiscountAmount(BigDecimal.valueOf(40_000));
        assertThat(BirthdayRewardService.describe(amount)).isEqualTo("Giảm 40.000đ");
    }

    @Test
    void aFixedAmountDiscountIsNeverMoreThanTheOrder() {
        Promotion amount = new Promotion();
        amount.setDiscountAmount(BigDecimal.valueOf(40_000));
        assertThat(PromotionService.discountFor(amount, BigDecimal.valueOf(150_000))).isEqualByComparingTo("40000");
        assertThat(PromotionService.discountFor(amount, BigDecimal.valueOf(30_000))).isEqualByComparingTo("30000");
    }

    @Test
    void aPersonalCodeIsOnlyForItsOwner() {
        User owner = user(Role.USER, true, true, true);
        owner.setId(1L);
        User other = user(Role.USER, true, true, true);
        other.setId(2L);
        Promotion personal = new Promotion();
        personal.setOwner(owner);
        personal.setActive(true);
        personal.setDiscountPercent(20);
        personal.setValidFrom(LocalDate.of(2026, 11, 1));
        personal.setValidTo(LocalDate.of(2026, 11, 30));
        LocalDate today = LocalDate.of(2026, 11, 20);

        assertThatCode(() -> PromotionService.checkUsable(personal, BigDecimal.valueOf(100_000), today, owner))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> PromotionService.checkUsable(personal, BigDecimal.valueOf(100_000), today, other))
                .hasMessage(PromotionService.NOT_YOUR_CODE);
        assertThatThrownBy(() -> PromotionService.checkUsable(personal, BigDecimal.valueOf(100_000), today, null))
                .as("no account (e.g. a counter sale)").hasMessage(PromotionService.NOT_YOUR_CODE);
        assertThatThrownBy(() -> PromotionService.checkUsable(personal, BigDecimal.valueOf(100_000), LocalDate.of(2026, 12, 1), owner))
                .hasMessage("Mã giảm giá không trong thời gian áp dụng");

        Promotion general = new Promotion();
        general.setActive(true);
        general.setDiscountPercent(10);
        general.setValidFrom(LocalDate.of(2026, 1, 1));
        general.setValidTo(LocalDate.of(2026, 12, 31));
        assertThatCode(() -> PromotionService.checkUsable(general, BigDecimal.valueOf(100_000), today, other))
                .doesNotThrowAnyException();
    }

    @Test
    void sampleAccountsAreNeverReallyMailed() {
        assertThat(MailService.isSampleAccount("teen@bbcinema.vn")).isTrue();
        assertThat(MailService.isSampleAccount("User@BBCinema.vn")).isTrue();
        assertThat(MailService.isSampleAccount("someone@gmail.com")).isFalse();
    }

    private static BirthdayRewardForm form(DiscountType type, long value) {
        BirthdayRewardForm form = new BirthdayRewardForm();
        form.setDiscountType(type);
        form.setDiscountValue(BigDecimal.valueOf(value));
        return form;
    }

    private static User user(Role role, boolean verified, boolean birthDate, boolean enabled) {
        User user = new User();
        user.setRole(role);
        user.setEnabled(enabled);
        user.setEmailVerifiedAt(verified ? LocalDateTime.of(2026, 1, 1, 0, 0) : null);
        user.setBirthDate(birthDate ? LocalDate.of(2000, 11, 20) : null);
        return user;
    }
}
