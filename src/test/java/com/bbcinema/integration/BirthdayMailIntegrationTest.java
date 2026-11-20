package com.bbcinema.integration;

import com.bbcinema.dto.request.BirthdayRewardForm;
import com.bbcinema.entity.Promotion;
import com.bbcinema.entity.User;
import com.bbcinema.entity.enums.DiscountType;
import com.bbcinema.entity.enums.Role;
import com.bbcinema.repository.BirthdayRewardRepository;
import com.bbcinema.service.BirthdayRewardService;
import com.bbcinema.service.DemoMailbox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

/** The birthday e-mail, read from the demo mailbox (real mail stays off in tests). */
@TestPropertySource(properties = "app.demo-mailbox=true")
class BirthdayMailIntegrationTest extends IntegrationTestBase {

    @Autowired BirthdayRewardService birthdayRewardService;
    @Autowired BirthdayRewardRepository rewardRepository;
    @Autowired DemoMailbox mailbox;

    @AfterEach
    void turnOff() {
        BirthdayRewardForm off = new BirthdayRewardForm();
        off.setEnabled(false);
        off.setDiscountType(DiscountType.PERCENT);
        off.setDiscountValue(BigDecimal.valueOf(20));
        birthdayRewardService.saveSettings(off);
    }

    @Test
    void theMemberIsMailedTheirCodeItsValueAndValidity() throws Exception {
        BirthdayRewardForm form = new BirthdayRewardForm();
        form.setEnabled(true);
        form.setDiscountType(DiscountType.AMOUNT);
        form.setDiscountValue(BigDecimal.valueOf(45_000));
        form.setMinOrder(BigDecimal.valueOf(90_000));
        birthdayRewardService.saveSettings(form);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        int n = unique();
        User member = userService.createAccount("sinhnhat" + n + "@test.vn", "Lê Sinh Nhật", "Secret@123", Role.USER);
        member.setBirthDate(today.withYear(2000).withDayOfMonth(1));
        userRepository.save(member);

        birthdayRewardService.issueDue();

        Promotion code = rewardRepository.findForMember(member.getId(), today.getYear()).orElseThrow().getPromotion();
        DemoMailbox.DemoMail mail = null;
        for (int i = 0; i < 100 && mail == null; i++) {
            mail = mailbox.all().stream().filter(m -> m.to().equals(member.getEmail())).findFirst().orElse(null);
            if (mail == null) {
                Thread.sleep(50); // the listener runs in the background, after the commit
            }
        }
        assertThat(mail).as("birthday mail in the demo mailbox").isNotNull();
        assertThat(mail.subject()).isEqualTo("[BBCinema] Chúc mừng sinh nhật Lê Sinh Nhật!");
        assertThat(mail.html())
                .contains("Chúc mừng sinh nhật", code.getCode(), "Giảm 45.000đ", "90.000đ",
                        code.getValidTo().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")), "/movies");
    }
}
