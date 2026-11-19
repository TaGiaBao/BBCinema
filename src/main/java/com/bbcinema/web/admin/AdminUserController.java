package com.bbcinema.web.admin;

import com.bbcinema.entity.enums.BookingStatus;
import com.bbcinema.entity.enums.Role;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.security.AppUserPrincipal;
import com.bbcinema.service.BookingService;
import com.bbcinema.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** User accounts and the list of all bookings. */
@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserService userService;
    private final BookingService bookingService;

    @GetMapping("/users")
    public String users(@RequestParam(required = false) String q,
                        @RequestParam(required = false) Role role,
                        @RequestParam(defaultValue = "0") int page,
                        Model model) {
        model.addAttribute("page", userService.search(q, role, PageRequest.of(Math.max(page, 0), 20)));
        model.addAttribute("q", q);
        model.addAttribute("role", role);
        model.addAttribute("roles", Role.values());
        model.addAttribute("menu", "users");
        return "admin/users";
    }

    @PostMapping("/users/{id}")
    public String updateUser(@PathVariable Long id, @RequestParam Role role,
                             @RequestParam(defaultValue = "false") boolean enabled,
                             @AuthenticationPrincipal AppUserPrincipal admin,
                             RedirectAttributes redirect) {
        try {
            userService.updateByAdmin(admin.getId(), id, role, enabled);
            redirect.addFlashAttribute("success", "Đã cập nhật tài khoản");
        } catch (BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/users";
    }

    @GetMapping("/bookings")
    public String bookings(@RequestParam(required = false) BookingStatus status,
                           @RequestParam(required = false) String q,
                           @RequestParam(defaultValue = "0") int page,
                           Model model) {
        model.addAttribute("page", bookingService.search(status, q, PageRequest.of(Math.max(page, 0), 20)));
        model.addAttribute("status", status);
        model.addAttribute("q", q);
        model.addAttribute("statuses", BookingStatus.values());
        model.addAttribute("menu", "bookings");
        return "admin/bookings";
    }
}
