package com.bbcinema.integration;

import com.bbcinema.entity.Showtime;
import com.bbcinema.entity.User;
import com.bbcinema.entity.enums.Role;
import com.bbcinema.entity.enums.ScreenFormat;
import com.bbcinema.entity.enums.SeatType;
import com.bbcinema.service.RoomService;
import com.bbcinema.service.RoomService.LayoutSeat;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** The drag-and-drop seat layout editor, end to end: admin saves a layout, customers see it. */
class RoomLayoutIntegrationTest extends IntegrationTestBase {

    @Autowired ObjectMapper json;

    private Map<String, LayoutSeat> byName(Long roomId) {
        return roomService.layout(roomId).seats().stream()
                .collect(Collectors.toMap(s -> s.row() + s.number(), Function.identity()));
    }

    private org.springframework.test.web.servlet.ResultActions save(Cookie admin, Long roomId, List<LayoutSeat> seats)
            throws Exception {
        return mvc.perform(post("/admin/cinemas/rooms/" + roomId + "/layout").with(csrf()).cookie(admin)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(seats)));
    }

    private static LayoutSeat with(LayoutSeat s, String row, int number, SeatType type, boolean active, int x, int y) {
        return new LayoutSeat(s.id(), row, number, type, active, x, y);
    }

    @Test
    void adminRearrangesRoomAndCustomersSeeTheNewLayout() throws Exception {
        // Room of 3 rows x 4 seats (A-B standard, C VIP) with one showtime tomorrow.
        Showtime st = showtimeTomorrow();
        Long roomId = st.getRoom().getId();
        Cookie admin = authCookie(account(Role.ADMIN));

        mvc.perform(get("/admin/cinemas/rooms/" + roomId).cookie(admin))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/js/seat-layout-editor.js")))
                .andExpect(content().string(containsString("data-layout=")));

        Map<String, LayoutSeat> seats = byName(roomId);
        List<LayoutSeat> layout = new ArrayList<>();
        for (LayoutSeat s : seats.values()) {
            String name = s.row() + s.number();
            switch (name) {
                // Aisle: A3 and A4 move two columns right.
                case "A3", "A4" -> layout.add(with(s, s.row(), s.number(), s.type(), true, s.x() + 2, s.y()));
                // Swap the names of B1 and B2.
                case "B1" -> layout.add(with(s, "B", 2, s.type(), true, s.x(), s.y()));
                case "B2" -> layout.add(with(s, "B", 1, s.type(), true, s.x(), s.y()));
                // C4 becomes an aisle (not sold in new showtimes).
                case "C4" -> layout.add(with(s, "C", 4, s.type(), false, s.x(), s.y()));
                default -> layout.add(s);
            }
        }
        // A couple seat after an empty walkway line (line 3 stays empty).
        layout.add(new LayoutSeat(null, "D", 1, SeatType.COUPLE, true, 1, 4));

        save(admin, roomId, layout)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", containsString("Đã lưu sơ đồ ghế")));

        Map<String, LayoutSeat> after = byName(roomId);
        assertThat(after.get("A3").x()).isEqualTo(seats.get("A3").x() + 2);
        assertThat(after.get("B1").id()).isEqualTo(seats.get("B2").id());
        assertThat(after.get("B2").id()).isEqualTo(seats.get("B1").id());
        assertThat(after.get("C4").active()).isFalse();
        assertThat(after.get("D1").type()).isEqualTo(SeatType.COUPLE);
        assertThat(after.get("D1").y()).isEqualTo(4);

        // The existing showtime is drawn with the new positions: A3 is now in grid column 5.
        Cookie member = authCookie(member(25));
        mvc.perform(get("/booking/showtime/" + st.getId()).cookie(member))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("grid-column:5 / span 1")));

        // A showtime created afterwards sells the new couple seat and not the aisle C4.
        Showtime later = showtimeService.create(st.getMovie(), st.getRoom(),
                st.getStartTime().plusHours(4), ScreenFormat.TWO_D, false);
        String map = mvc.perform(get("/api/showtimes/" + later.getId() + "/seats"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(map).contains("\"label\":\"D1\"").doesNotContain("\"label\":\"C4\"");
        assertThat(json.readTree(map).path("columns").asInt()).isEqualTo(6);
        // The empty line before row D is drawn as a walkway.
        mvc.perform(get("/booking/showtime/" + later.getId()).cookie(member))
                .andExpect(content().string(containsString("seat-row walkway")))
                .andExpect(content().string(containsString("grid-column:2 / span 2")));
    }

    @Test
    void seatsInUseCannotBeDeletedAndSoldSeatsKeepTheirName() throws Exception {
        Showtime st = showtimeTomorrow();
        Long roomId = st.getRoom().getId();
        Cookie admin = authCookie(account(Role.ADMIN));
        User buyer = member(30);
        Map<String, LayoutSeat> seats = byName(roomId);
        // A1 is held by a customer for the upcoming showtime.
        Long a1ShowtimeSeat = showtimeSeatRepository.findSeatMap(st.getId()).stream()
                .filter(ss -> ss.getSeat().getLabel().equals("A1")).findFirst().orElseThrow().getId();
        bookingService.holdSeats(buyer.getId(), st.getId(), List.of(a1ShowtimeSeat));

        // Deleting B4, which the showtime uses, is refused.
        List<LayoutSeat> withoutB4 = seats.values().stream().filter(s -> !(s.row() + s.number()).equals("B4")).toList();
        save(admin, roomId, withoutB4)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Không xoá được ghế B4")));

        // Renaming the held seat A1 is refused; moving it is fine.
        List<LayoutSeat> renamed = seats.values().stream()
                .map(s -> (s.row() + s.number()).equals("A1") ? with(s, "Z", 1, s.type(), true, s.x(), s.y()) : s)
                .toList();
        save(admin, roomId, renamed)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("không đổi tên được")));

        LayoutSeat a1 = seats.get("A1");
        List<LayoutSeat> moved = new ArrayList<>(seats.values().stream()
                .filter(s -> !(s.row() + s.number()).equals("A1")).toList());
        moved.add(with(a1, "A", 1, a1.type(), true, 10, 0));
        save(admin, roomId, moved).andExpect(status().isOk());
        assertThat(byName(roomId).get("A1").x()).isEqualTo(10);

        // Overlapping seats are refused with the names of both seats.
        List<LayoutSeat> overlap = new ArrayList<>(byName(roomId).values());
        overlap.add(new LayoutSeat(null, "X", 1, SeatType.STANDARD, true, 10, 0));
        save(admin, roomId, overlap)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("chồng lên ghế A1")));
    }

    @Test
    void onlyAdminsCanSaveLayouts() throws Exception {
        Showtime st = showtimeTomorrow();
        Long roomId = st.getRoom().getId();
        List<LayoutSeat> layout = roomService.layout(roomId).seats();
        save(authCookie(account(Role.STAFF)), roomId, layout).andExpect(status().isForbidden());
        mvc.perform(post("/admin/cinemas/rooms/" + roomId + "/layout").cookie(authCookie(account(Role.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(layout)))
                .andExpect(status().isForbidden()); // no CSRF token
        mvc.perform(get("/booking/showtime/" + st.getId()).cookie(authCookie(member(20))))
                .andExpect(content().string(not(containsString("seat gap"))));
    }
}
