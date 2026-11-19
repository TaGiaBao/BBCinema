package com.bbcinema.integration;

import com.bbcinema.entity.User;
import com.bbcinema.service.PasswordResetService;
import com.bbcinema.service.event.PasswordResetRequestedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@RecordApplicationEvents
class PasswordResetIntegrationTest extends IntegrationTestBase {

    @Autowired ApplicationEvents events;
    @Autowired PasswordResetService passwordResetService;

    private String requestLinkToken(User user) throws Exception {
        mvc.perform(post("/forgot-password").with(csrf()).param("email", user.getEmail().toUpperCase()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("bạn sẽ nhận được email")));
        PasswordResetRequestedEvent event = events.stream(PasswordResetRequestedEvent.class)
                .filter(e -> e.email().equals(user.getEmail())).reduce((a, b) -> b).orElseThrow();
        assertThat(event.link()).contains("/reset-password?token=");
        return event.link().substring(event.link().indexOf("token=") + 6);
    }

    @Test
    void memberResetsPasswordWithEmailedLinkOnce() throws Exception {
        User member = member(25);
        String token = requestLinkToken(member);

        mvc.perform(get("/reset-password").param("token", token))
                .andExpect(content().string(containsString("Lưu mật khẩu mới")));
        mvc.perform(post("/reset-password").with(csrf()).param("token", token)
                        .param("password", "NewPass@1").param("confirmPassword", "Mismatch"))
                .andExpect(content().string(containsString("không khớp")));
        mvc.perform(post("/reset-password").with(csrf()).param("token", token)
                        .param("password", "NewPass@1").param("confirmPassword", "NewPass@1"))
                .andExpect(redirectedUrl("/login"));

        // The new password works, the old one does not, and the link cannot be used again.
        mvc.perform(post("/login").with(csrf()).param("email", member.getEmail()).param("password", "NewPass@1"))
                .andExpect(redirectedUrl("/"));
        mvc.perform(post("/login").with(csrf()).param("email", member.getEmail()).param("password", "Secret@123"))
                .andExpect(content().string(containsString("Email hoặc mật khẩu không đúng")));
        mvc.perform(get("/reset-password").param("token", token))
                .andExpect(content().string(containsString("không hợp lệ hoặc đã hết hạn")));
    }

    @Test
    void unknownAddressGetsTheSameAnswerAndNoMail() throws Exception {
        long before = events.stream(PasswordResetRequestedEvent.class).count();
        mvc.perform(post("/forgot-password").with(csrf()).param("email", "nobody" + unique() + "@test.vn"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("bạn sẽ nhận được email")));
        assertThat(events.stream(PasswordResetRequestedEvent.class).count()).isEqualTo(before);
    }

    @Test
    void expiredLinkIsRefusedAndTokenIsStoredOnlyAsHash() throws Exception {
        User member = member(30);
        String token = requestLinkToken(member);
        User saved = userRepository.findById(member.getId()).orElseThrow();
        assertThat(saved.getResetTokenHash()).hasSize(64).isNotEqualTo(token);

        saved.setResetTokenExpiresAt(LocalDateTime.now().minusMinutes(1));
        userRepository.save(saved);
        assertThat(passwordResetService.isValid(token)).isFalse();
        mvc.perform(post("/reset-password").with(csrf()).param("token", token)
                        .param("password", "NewPass@1").param("confirmPassword", "NewPass@1"))
                .andExpect(content().string(containsString("không hợp lệ hoặc đã hết hạn")));
    }

    @Test
    void loginPagesLinkToForgotPassword() throws Exception {
        mvc.perform(get("/login")).andExpect(content().string(containsString("href=\"/forgot-password\"")));
        mvc.perform(get("/internal/login")).andExpect(content().string(containsString("href=\"/forgot-password\"")));
        mvc.perform(get("/forgot-password")).andExpect(status().isOk());
    }
}
