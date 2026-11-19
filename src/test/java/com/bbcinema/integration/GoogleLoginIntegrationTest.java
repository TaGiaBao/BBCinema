package com.bbcinema.integration;

import com.bbcinema.entity.User;
import com.bbcinema.entity.enums.Role;
import com.bbcinema.security.AppUserPrincipal;
import com.bbcinema.service.GoogleLoginService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

/** Google sign-in rules. Google itself is not called; the button is off without GOOGLE_CLIENT_ID. */
class GoogleLoginIntegrationTest extends IntegrationTestBase {

    @Autowired GoogleLoginService googleLoginService;

    @Test
    void newGoogleAddressBecomesAMemberAndExistingMemberSignsIntoOwnAccount() {
        int n = unique();
        AppUserPrincipal created = googleLoginService.signIn("New.Google" + n + "@Gmail.com", true, "Nguyễn Google");
        User user = userRepository.findById(created.getId()).orElseThrow();
        assertThat(user.getEmail()).isEqualTo("new.google" + n + "@gmail.com");
        assertThat(user.getFullName()).isEqualTo("Nguyễn Google");
        assertThat(user.getRole()).isEqualTo(Role.USER);

        User member = member(22);
        assertThat(googleLoginService.signIn(member.getEmail(), true, "Khác").getId()).isEqualTo(member.getId());
    }

    @Test
    void unverifiedAddressesInternalAccountsAndLockedMembersAreRefused() {
        assertThatThrownBy(() -> googleLoginService.signIn("x" + unique() + "@gmail.com", false, "X"))
                .hasFieldOrPropertyWithValue("code", GoogleLoginService.UNVERIFIED);
        User staff = account(Role.STAFF);
        assertThatThrownBy(() -> googleLoginService.signIn(staff.getEmail(), true, "S"))
                .hasFieldOrPropertyWithValue("code", GoogleLoginService.INTERNAL);
        User locked = member(30);
        locked.setEnabled(false);
        userRepository.save(locked);
        assertThatThrownBy(() -> googleLoginService.signIn(locked.getEmail(), true, "L"))
                .hasFieldOrPropertyWithValue("code", GoogleLoginService.LOCKED);
    }

    @Test
    void buttonIsHiddenWhenGoogleIsNotConfiguredAndErrorsAreExplained() throws Exception {
        mvc.perform(get("/login")).andExpect(content().string(not(containsString("Đăng nhập bằng Google"))));
        mvc.perform(get("/login").param("google", GoogleLoginService.INTERNAL))
                .andExpect(content().string(containsString("Vui lòng dùng trang đăng nhập nội bộ")));
    }
}
