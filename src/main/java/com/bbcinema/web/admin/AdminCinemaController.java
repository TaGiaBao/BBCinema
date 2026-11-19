package com.bbcinema.web.admin;

import com.bbcinema.dto.request.CinemaForm;
import com.bbcinema.dto.request.RoomForm;
import com.bbcinema.entity.Room;
import com.bbcinema.entity.Seat;
import com.bbcinema.entity.enums.SeatType;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.service.CinemaService;
import com.bbcinema.service.RoomService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Cinemas, their rooms, and the seat layout editor. */
@Controller
@RequestMapping("/admin/cinemas")
@RequiredArgsConstructor
public class AdminCinemaController {

    private final CinemaService cinemaService;
    private final RoomService roomService;

    @ModelAttribute("menu")
    public String menu() {
        return "cinemas";
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("allCinemas", cinemaService.findAll());
        model.addAttribute("rooms", roomService.findAll());
        if (!model.containsAttribute("cinemaForm")) {
            model.addAttribute("cinemaForm", new CinemaForm());
        }
        if (!model.containsAttribute("roomForm")) {
            model.addAttribute("roomForm", new RoomForm());
        }
        return "admin/cinemas/list";
    }

    @GetMapping("/{id}/edit")
    public String editCinema(@PathVariable Long id, Model model) {
        model.addAttribute("cinemaForm", cinemaService.toForm(cinemaService.getById(id)));
        return list(model);
    }

    @PostMapping("/save")
    public String saveCinema(@Valid @ModelAttribute("cinemaForm") CinemaForm form, BindingResult binding,
                             Model model, RedirectAttributes redirect) {
        if (binding.hasErrors()) {
            return list(model);
        }
        cinemaService.save(form);
        redirect.addFlashAttribute("success", "Đã lưu rạp " + form.getName());
        return "redirect:/admin/cinemas";
    }

    @PostMapping("/rooms")
    public String createRoom(@Valid @ModelAttribute("roomForm") RoomForm form, BindingResult binding,
                             Model model, RedirectAttributes redirect) {
        if (binding.hasErrors()) {
            return list(model);
        }
        try {
            Room room = roomService.create(form);
            redirect.addFlashAttribute("success", "Đã tạo " + room.getName() + " với " + room.getSeats().size() + " ghế");
            return "redirect:/admin/cinemas/rooms/" + room.getId();
        } catch (BusinessException e) {
            binding.reject("room", e.getMessage());
            return list(model);
        }
    }

    @GetMapping("/rooms/{id}")
    public String roomLayout(@PathVariable Long id, Model model) {
        Room room = roomService.getById(id);
        Map<String, List<Seat>> rows = roomService.getSeats(id).stream()
                .collect(Collectors.groupingBy(Seat::getRowLabel, TreeMap::new, Collectors.toList()));
        model.addAttribute("room", room);
        model.addAttribute("rows", rows);
        model.addAttribute("seatTypes", SeatType.values());
        model.addAttribute("hasShowtimes", roomService.hasShowtimes(id));
        return "admin/cinemas/room";
    }

    @PostMapping("/rooms/{id}")
    public String updateRoom(@PathVariable Long id, @RequestParam String name,
                             @RequestParam(defaultValue = "false") boolean active, RedirectAttributes redirect) {
        try {
            roomService.rename(id, name, active);
            redirect.addFlashAttribute("success", "Đã lưu thông tin phòng");
        } catch (BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/cinemas/rooms/" + id;
    }

    /** Called by the seat editor (JavaScript) when the admin clicks a seat. */
    @PostMapping("/rooms/{roomId}/seats/{seatId}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> updateSeat(@PathVariable Long roomId, @PathVariable Long seatId,
                                                          @RequestParam SeatType type,
                                                          @RequestParam boolean active) {
        Seat seat = roomService.updateSeat(roomId, seatId, type, active);
        return ResponseEntity.ok(Map.of("id", seat.getId(), "type", seat.getType().name(), "active", seat.isActive()));
    }
}
