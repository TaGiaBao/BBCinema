package com.bbcinema.integration;

import com.bbcinema.entity.User;
import com.bbcinema.service.event.EmailVerificationRequestedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** New members must confirm their e-mail address (tickets are sent there) before they can sign in. */
@RecordApplicationEvents
class EmailVerificationIntegrationTest extends IntegrationTestBase {

    @Autowired ApplicationEvents events;

    private String register(String email) throws Exception {
        mvc.perform(post("/register").with(csrf())
                        .param("fullName", "Khách Mới").param("email", email).param("birthDate", "2000-05-06")
                        .param("password", "abc123").param("confirmPassword", "abc123"))
                .andExpect(redirectedUrl("/register/check-email"));
        return lastLinkFor(email);
    }

    private String lastLinkFor(String email) {
        EmailVerificationRequestedEvent event = events.stream(EmailVerificationRequestedEvent.class)
                .filter(e -> e.email().equals(email)).reduce((a, b) -> b).orElseThrow();
        assertThat(event.link()).contains("/verify-email?token=");
        return event.link().substring(event.link().indexOf("/verify-email"));
    }

    private void login(String email, String expectedText) throws Exception {
        mvc.perform(post("/login").with(csrf()).param("email", email).param("password", "abc123"))
                .andExpect(content().string(containsString(expectedText)));
    }

    @Test
    void memberSignsInOnlyAfterOpeningTheConfirmationLink() throws Exception {
        String email = "confirm" + unique() + "@test.vn";
        String link = register(email);
        mvc.perform(get("/register/check-email").flashAttr("email", email))
                .andExpect(content().string(containsString(email)));

        login(email, "chưa được xác nhận");
        mvc.perform(post("/login").with(csrf()).param("email", email).param("password", "abc123"))
                .andExpect(cookie().doesNotExist("BB_TOKEN"))
                .andExpect(content().string(containsString("Gửi lại email xác nhận")));

        mvc.perform(get(link)).andExpect(redirectedUrl("/login")).andExpect(flash().attribute("success", containsString(email)));
        mvc.perform(post("/login").with(csrf()).param("email", email).param("password", "abc123"))
                .andExpect(redirectedUrl("/")).andExpect(cookie().exists("BB_TOKEN"));

        User user = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(user.isEmailVerified()).isTrue();
        assertThat(user.getVerifyTokenHash()).isNull();
        mvc.perform(get(link)).andExpect(flash().attribute("error", containsString("không hợp lệ"))); // works once
    }

    @Test
    void confirmationCanBeResentAndOnlyTheNewestLinkWorks() throws Exception {
        String email = "resend" + unique() + "@test.vn";
        String first = register(email);
        mvc.perform(post("/verify-email/resend").with(csrf()).param("email", email.toUpperCase()))
                .andExpect(redirectedUrl("/register/check-email"))
                .andExpect(flash().attribute("resent", true));
        String second = lastLinkFor(email);
        assertThat(second).isNotEqualTo(first);
        mvc.perform(get(first)).andExpect(flash().attribute("error", containsString("không hợp lệ")));
        mvc.perform(get(second)).andExpect(flash().attribute("success", containsString(email)));

        // Unknown addresses get the same answer and no mail.
        long before = events.stream(EmailVerificationRequestedEvent.class).count();
        mvc.perform(post("/verify-email/resend").with(csrf()).param("email", "nobody@test.vn"))
                .andExpect(flash().attribute("resent", true));
        assertThat(events.stream(EmailVerificationRequestedEvent.class).count()).isEqualTo(before);
    }

    @Test
    void apiSignUpAlsoWaitsForConfirmation() throws Exception {
        String email = "api" + unique() + "@test.vn";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Khách API\",\"email\":\"" + email + "\",\"birthDate\":\"2000-05-06\",\"password\":\"abc123\",\"confirmPassword\":\"abc123\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"abc123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("chưa được xác nhận")));
    }

    @Test
    void accountsMadeByTheCinemaNeedNoConfirmation() {
        assertThat(member(30).isEmailVerified()).isTrue();
    }
}
