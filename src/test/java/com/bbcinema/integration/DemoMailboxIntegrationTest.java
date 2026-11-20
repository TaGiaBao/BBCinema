package com.bbcinema.integration;

import com.bbcinema.service.DemoMailbox;
import com.bbcinema.service.MailService;
import com.bbcinema.service.event.PasswordResetRequestedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** With the demo mailbox on and real mail off, sent e-mails can be read at /demo/mailbox. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.demo-mailbox=true")
class DemoMailboxIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired MailService mailService;
    @Autowired DemoMailbox mailbox;

    @Test
    void passwordResetMailIsKeptAndShownWithoutAMailServer() throws Exception {
        mailService.onPasswordResetRequested(new PasswordResetRequestedEvent(
                "demo@test.vn", "Khách Demo", "http://localhost:8080/reset-password?token=abc"));
        for (int i = 0; i < 100 && mailbox.all().isEmpty(); i++) {
            Thread.sleep(50); // the listener runs in the background
        }
        assertThat(mailbox.all()).isNotEmpty();
        long id = mailbox.all().get(0).id();

        mvc.perform(get("/demo/mailbox"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("demo@test.vn")))
                .andExpect(content().string(containsString("[BBCinema] Đặt lại mật khẩu")));
        mvc.perform(get("/demo/mailbox/" + id))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("reset-password?token=abc")));
        mvc.perform(get("/demo/mailbox/999999")).andExpect(status().isNotFound());
    }
}
