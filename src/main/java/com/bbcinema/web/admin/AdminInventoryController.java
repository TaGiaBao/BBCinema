package com.bbcinema.web.admin;

import com.bbcinema.entity.Cinema;
import com.bbcinema.entity.User;
import com.bbcinema.entity.enums.Role;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.security.AppUserPrincipal;
import com.bbcinema.service.CinemaService;
import com.bbcinema.service.InventoryService;
import com.bbcinema.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Snack-bar stock. Counter managers see their cinema's stock and enter goods-received notes; the general manager
 * sees every cinema, approves or rejects the notes and keeps the item list.
 */
@Controller
@RequestMapping("/admin/inventory")
@RequiredArgsConstructor
public class AdminInventoryController {

    private final InventoryService inventoryService;
    private final UserService userService;
    private final CinemaService cinemaService;

    @GetMapping
    public String page(@RequestParam(required = false) Long cinemaId, @AuthenticationPrincipal AppUserPrincipal viewer, Model model) {
        User user = userService.getById(viewer.getId());
        boolean admin = user.getRole() == Role.ADMIN;
        List<Cinema> cinemas = cinemaService.findActive();
        Cinema selected = admin
                ? cinemas.stream().filter(c -> c.getId().equals(cinemaId)).findFirst().orElse(cinemas.isEmpty() ? null : cinemas.get(0))
                : user.getCinema();
        model.addAttribute("isAdmin", admin);
        model.addAttribute("allCinemas", cinemas);
        model.addAttribute("selectedCinema", selected);
        model.addAttribute("stock", selected == null ? Map.of() : inventoryService.stockOf(selected.getId()));
        model.addAttribute("items", inventoryService.items());
        model.addAttribute("activeItems", inventoryService.activeItems());
        model.addAttribute("requests", inventoryService.requestsFor(user));
        model.addAttribute("menu", "inventory");
        return "admin/inventory";
    }

    /** Counter manager: a delivery for their cinema, sent to the general manager for approval. */
    @PostMapping("/requests")
    public String request(@RequestParam Map<String, String> params, @AuthenticationPrincipal AppUserPrincipal viewer,
                          RedirectAttributes redirect) {
        Map<Long, Integer> quantities = new HashMap<>();
        params.forEach((key, value) -> {
            if (key.startsWith("qty_") && value != null && !value.isBlank()) {
                try {
                    quantities.put(Long.parseLong(key.substring(4)), Integer.parseInt(value.trim()));
                } catch (NumberFormatException e) {
                    redirect.addFlashAttribute("error", "Số lượng phải là số nguyên");
                }
            }
        });
        try {
            var request = inventoryService.request(viewer.getId(), quantities, params.get("note"));
            redirect.addFlashAttribute("success", "Đã gửi phiếu nhập #" + request.getId() + " cho quản lý tổng duyệt");
        } catch (BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/inventory";
    }

    /** General manager: approve or reject a pending note. */
    @PostMapping("/requests/{id}/review")
    public String review(@PathVariable Long id, @RequestParam String decision,
                         @RequestParam(required = false) String reviewNote,
                         @AuthenticationPrincipal AppUserPrincipal viewer, RedirectAttributes redirect) {
        try {
            boolean approve = "approve".equals(decision);
            var request = inventoryService.review(id, viewer.getId(), approve, reviewNote);
            redirect.addFlashAttribute("success", (approve ? "Đã duyệt" : "Đã từ chối") + " phiếu nhập #" + id);
            return "redirect:/admin/inventory?cinemaId=" + request.getCinema().getId();
        } catch (BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/inventory";
        }
    }

    /** General manager: add an item to the stock list. */
    @PostMapping("/items")
    public String addItem(@RequestParam String name, @RequestParam String unit, RedirectAttributes redirect) {
        try {
            inventoryService.addItem(name, unit);
            redirect.addFlashAttribute("success", "Đã thêm mặt hàng " + name.trim());
        } catch (BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/inventory";
    }
}
