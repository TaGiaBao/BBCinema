package com.bbcinema.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** With a Google client id configured, the button appears and leads to Google's consent page. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"app.google.client-id=test-client-id", "app.google.client-secret=test-secret"})
class GoogleLoginEnabledIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void loginPageOffersGoogleAndTheButtonRedirectsToGoogle() throws Exception {
        mvc.perform(get("/login")).andExpect(content().string(containsString("href=\"/oauth2/authorization/google\"")));
        mvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", startsWith("https://accounts.google.com/o/oauth2/v2/auth")))
                .andExpect(header().string("Location", containsString("client_id=test-client-id")))
                .andExpect(header().string("Location", containsString("redirect_uri=http://localhost/login/oauth2/code/google")));
    }
}
