package com.bbcinema.web.admin;

import com.bbcinema.audit.Audited;
import com.bbcinema.dto.request.CinemaForm;
import com.bbcinema.dto.request.RoomForm;
import com.bbcinema.entity.Room;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.service.CinemaService;
import com.bbcinema.service.RoomService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/** Cinemas, their rooms, and the seat layout editor. */
@Controller
@RequestMapping("/admin/cinemas")
@RequiredArgsConstructor
public class AdminCinemaController {

    private final CinemaService cinemaService;
    private final RoomService roomService;
    private final ObjectMapper objectMapper;

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

    @Audited("Lưu rạp")
    @PostMapping("/save")
    public String saveCinema(@Valid @ModelAttribute("cinemaForm") CinemaForm form, BindingResult binding,
                             Model model, RedirectAttributes redirect) {
        if (binding.hasErrors()) {
            return list(model);
        }
        try {
            cinemaService.save(form);
        } catch (BusinessException e) {
            binding.reject("location", e.getMessage());
            model.addAttribute("error", e.getMessage());
            return list(model);
        }
        redirect.addFlashAttribute("success", "Đã lưu rạp " + form.getName());
        return "redirect:/admin/cinemas";
    }

    @Audited("Tạo phòng chiếu")
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
    public String roomLayout(@PathVariable Long id, Model model) throws JsonProcessingException {
        model.addAttribute("room", roomService.getById(id));
        model.addAttribute("layoutJson", objectMapper.writeValueAsString(roomService.layout(id)));
        model.addAttribute("hasShowtimes", roomService.hasShowtimes(id));
        return "admin/cinemas/room";
    }

    /** Saves the whole seat layout sent by the drag-and-drop editor (admin/cinemas/room.html). */
    @Audited("Sửa sơ đồ ghế")
    @PostMapping("/rooms/{id}/layout")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> saveLayout(@PathVariable Long id,
                                                          @RequestBody List<RoomService.LayoutSeat> seats) {
        try {
            Room room = roomService.saveLayout(id, seats);
            return ResponseEntity.ok(Map.of("message", "Đã lưu sơ đồ ghế (" + room.getActiveSeatCount() + " ghế bán được)",
                    "layout", roomService.layout(id)));
        } catch (BusinessException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @Audited("Sửa phòng chiếu")
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
}
