package com.bbcinema.integration;

import com.bbcinema.entity.Showtime;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** The customer site in Vietnamese (default) and English (?lang=en, kept in the BB_LANG cookie). */
class LanguageIntegrationTest extends IntegrationTestBase {

    private static final Cookie ENGLISH = new Cookie("BB_LANG", "en");

    @Test
    void vietnameseIsTheDefault() throws Exception {
        showtimeTomorrow();
        mvc.perform(get("/"))
                .andExpect(content().string(containsString("lang=\"vi\"")))
                .andExpect(content().string(containsString("Phim đang chiếu")))
                .andExpect(content().string(containsString("Lịch chiếu theo rạp")));
    }

    @Test
    void langParameterSwitchesToEnglishAndIsRemembered() throws Exception {
        showtimeTomorrow();
        mvc.perform(get("/").param("lang", "en"))
                .andExpect(cookie().value("BB_LANG", "en"))
                .andExpect(content().string(containsString("lang=\"en\"")))
                .andExpect(content().string(containsString("Now showing")))
                .andExpect(content().string(containsString("Showtimes by cinema")))
                .andExpect(content().string(not(containsString("Phim đang chiếu"))));
        mvc.perform(get("/prices").cookie(ENGLISH))
                .andExpect(content().string(containsString("Ticket prices")))
                .andExpect(content().string(containsString("Before 5 pm")));
        mvc.perform(get("/login").cookie(ENGLISH))
                .andExpect(content().string(containsString("Forgot password?")))
                .andExpect(content().string(containsString("Become a member")));
    }

    @Test
    void englishCoversTheBookingPagesLabelsAndScripts() throws Exception {
        Showtime st = showtimeTomorrow();
        mvc.perform(get("/booking/showtime/" + st.getId()).cookie(ENGLISH).cookie(authCookie(member(25))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("SCREEN")))
                .andExpect(content().string(containsString("Standard seat")))
                .andExpect(content().string(containsString("2D Subtitled")))
                .andExpect(content().string(containsString("\"noShowtimes\":\"No showtimes at {0}")))
                .andExpect(content().string(containsString("Seat A1, Standard")));
        mvc.perform(get("/schedule").cookie(ENGLISH)).andExpect(content().string(containsString("Today")));
    }

    @Test
    void unknownLanguagesAreIgnoredAndVietnameseComesBack() throws Exception {
        mvc.perform(get("/").param("lang", "fr")).andExpect(cookie().doesNotExist("BB_LANG"))
                .andExpect(content().string(containsString("lang=\"vi\"")));
        mvc.perform(get("/").param("lang", "vi").cookie(ENGLISH)).andExpect(cookie().value("BB_LANG", "vi"))
                .andExpect(content().string(containsString("Phim đang chiếu")));
    }
}
