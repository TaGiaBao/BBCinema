package com.bbcinema.integration;

import com.bbcinema.entity.Booking;
import com.bbcinema.entity.Showtime;
import com.bbcinema.entity.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiIntegrationTest extends IntegrationTestBase {

    @Autowired ObjectMapper json;

    @Test
    void loginReturnsTokenThatWorksAsBearer() throws Exception {
        User member = member(22);
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + member.getEmail() + "\",\"password\":\"Secret@123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn().getResponse().getContentAsString();
        String token = json.readTree(body).get("accessToken").asText();
        mvc.perform(get("/api/bookings/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    }

    @Test
    void wrongPasswordGives401() throws Exception {
        User member = member(22);
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + member.getEmail() + "\",\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Email hoặc mật khẩu không đúng"));
    }

    @Test
    void catalogEndpoints() throws Exception {
        Showtime st = showtimeTomorrow();
        String date = st.getStartTime().toLocalDate().toString();
        mvc.perform(get("/api/cinemas")).andExpect(status().isOk()).andExpect(jsonPath("$", not(empty())));
        mvc.perform(get("/api/movies/" + st.getMovie().getId())).andExpect(jsonPath("$.title").value(st.getMovie().getTitle()));
        mvc.perform(get("/api/movies/" + st.getMovie().getId() + "/showtimes")
                        .param("cinemaId", st.getRoom().getCinema().getId().toString()).param("date", date))
                .andExpect(jsonPath("$[0].id").value(st.getId()));
        mvc.perform(get("/api/showtimes/" + st.getId() + "/seats"))
                .andExpect(jsonPath("$.totalCount").value(12))
                .andExpect(jsonPath("$.rows", hasSize(3)));
        mvc.perform(get("/api/ticket-prices")).andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(24))));
        mvc.perform(get("/api/movies/999999")).andExpect(status().isNotFound());
    }

    @Test
    void bookingThroughApiAndConflict() throws Exception {
        Showtime st = showtimeTomorrow();
        List<Long> seats = freeSeats(st.getId(), 2);
        User first = member(30);
        User second = member(30);
        String request = "{\"showtimeId\":" + st.getId() + ",\"showtimeSeatIds\":" + seats + "}";

        String body = mvc.perform(post("/api/bookings").header("Authorization", bearer(first))
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.seats", hasSize(2)))
                .andReturn().getResponse().getContentAsString();
        String code = json.readTree(body).get("code").asText();

        mvc.perform(post("/api/bookings").header("Authorization", bearer(second))
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("vừa có người chọn")));

        mvc.perform(get("/api/bookings/" + code).header("Authorization", bearer(second))).andExpect(status().isForbidden());

        JsonNode paid = json.readTree(mvc.perform(post("/api/bookings/" + code + "/pay").header("Authorization", bearer(first)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(paid.get("status").asText()).isEqualTo("PAID");

        mvc.perform(delete("/api/bookings/" + code).header("Authorization", bearer(first)))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void validationErrorsListFields() throws Exception {
        User member = member(22);
        mvc.perform(post("/api/bookings").header("Authorization", bearer(member))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"showtimeSeatIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.showtimeId").exists())
                .andExpect(jsonPath("$.fieldErrors.showtimeSeatIds").exists());
    }

    @Test
    void openApiDocumentIsPublished() throws Exception {
        mvc.perform(get("/v3/api-docs/bbcinema")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/bookings']").exists());
    }

    @Test
    void seatApiShowsSeatsHeldOrSoldByOthers() throws Exception {
        Showtime st = showtimeTomorrow();
        User buyer = member(25);
        List<Long> seats = freeSeats(st.getId(), 2);
        bookingService.holdSeats(buyer.getId(), st.getId(), List.of(seats.get(0)));
        Booking paid = bookingService.holdSeats(buyer.getId(), st.getId(), List.of(seats.get(1)));
        bookingService.payWithMock(paid.getCode(), buyer.getId());

        // The seat map page polls this endpoint anonymously.
        String body = mvc.perform(get("/api/showtimes/" + st.getId() + "/seats"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode rows = json.readTree(body).path("rows");
        String held = null;
        String booked = null;
        for (JsonNode row : rows) {
            for (JsonNode cell : row.path("cells")) {
                long id = cell.path("showtimeSeatId").asLong();
                if (id == seats.get(0)) {
                    held = cell.path("status").asText();
                } else if (id == seats.get(1)) {
                    booked = cell.path("status").asText();
                }
            }
        }
        assertThat(held).isEqualTo("HELD");
        assertThat(booked).isEqualTo("BOOKED");
    }
}
