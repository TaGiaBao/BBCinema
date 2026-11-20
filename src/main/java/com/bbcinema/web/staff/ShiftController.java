package com.bbcinema.web.staff;

import com.bbcinema.config.AppProperties;
import com.bbcinema.entity.Booking;
import com.bbcinema.entity.Cinema;
import com.bbcinema.entity.CounterShift;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.security.AppUserPrincipal;
import com.bbcinema.service.ShiftService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;

/**
 * Till shifts at the counter: a manager opens the shift with the opening float, approves refunds, and closes
 * the shift after counting the drawer. Closing logs the staff member out and shows the shift report.
 */
@Controller
@RequiredArgsConstructor
public class ShiftController {

    public static final String OPEN_PATH = "/staff/shift/open";
    public static final String CLOSE_PATH = "/staff/shift/close";

    private final ShiftService shiftService;
    private final AppProperties properties;

    @GetMapping(OPEN_PATH)
    public String openPage(@AuthenticationPrincipal AppUserPrincipal staff, Model model) {
        if (staff.getRole() != com.bbcinema.entity.enums.Role.STAFF) {
            return "redirect:/staff/shifts"; // the counter manager does not sell, they look after the shifts
        }
        if (shiftService.findOpen(staff.getId()).isPresent()) {
            return "redirect:/staff";
        }
        model.addAttribute("suggestedFloat", shiftService.suggestedFloat());
        model.addAttribute("menu", "staff");
        return "staff/shift-open";
    }

    @PostMapping(OPEN_PATH)
    public String open(@AuthenticationPrincipal AppUserPrincipal staff,
                       @ModelAttribute("currentCinema") Cinema cinema,
                       @RequestParam(required = false) BigDecimal openingFloat,
                       @RequestParam(required = false) String managerEmail,
                       @RequestParam(required = false) String managerPassword,
                       RedirectAttributes redirect) {
        try {
            CounterShift shift = shiftService.open(staff.getId(), cinema == null ? null : cinema.getId(),
                    managerEmail, managerPassword, openingFloat);
            redirect.addFlashAttribute("success", "Đã vào ca. Quỹ đầu ca " + money(shift.getOpeningFloat()) + ".");
            return "redirect:/staff";
        } catch (BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:" + OPEN_PATH;
        }
    }

    @GetMapping(CLOSE_PATH)
    public String closePage(@AuthenticationPrincipal AppUserPrincipal staff, Model model, RedirectAttributes redirect) {
        return shiftService.findOpen(staff.getId()).map(shift -> {
            model.addAttribute("summary", shiftService.summary(shift));
            model.addAttribute("menu", "staff");
            return "staff/shift-close";
        }).orElse("redirect:" + OPEN_PATH);
    }

    /** The manager counted the drawer: the shift closes, the staff member is logged out and sees the report. */
    @PostMapping(CLOSE_PATH)
    public String close(@AuthenticationPrincipal AppUserPrincipal staff,
                        @RequestParam(required = false) BigDecimal countedCash,
                        @RequestParam(required = false) String note,
                        @RequestParam(required = false) String managerEmail,
                        @RequestParam(required = false) String managerPassword,
                        HttpServletResponse response, Model model, RedirectAttributes redirect) {
        CounterShift shift;
        try {
            shift = shiftService.close(staff.getId(), managerEmail, managerPassword, countedCash, note);
        } catch (BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:" + CLOSE_PATH;
        }
        response.addHeader("Set-Cookie", properties.jwt().cookieName() + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
        SecurityContextHolder.clearContext();
        model.addAttribute("shift", shiftService.getDetailed(shift.getId()));
        model.addAttribute("loggedOut", true);
        return "staff/shift-report";
    }

    /** Counter manager at the counter: the open shifts of their cinema, e.g. one a staff member left open. */
    @GetMapping("/staff/shifts")
    public String openShifts(@AuthenticationPrincipal AppUserPrincipal manager, Model model) {
        if (manager.getRole() != com.bbcinema.entity.enums.Role.MANAGER) {
            return "redirect:/staff";
        }
        model.addAttribute("shifts", shiftService.openAtCinemaOf(manager.getId()).stream().map(shiftService::summary).toList());
        model.addAttribute("menu", "shifts-open");
        return "staff/shifts-open";
    }

    @GetMapping("/staff/shifts/{id}/close")
    public String closeShiftPage(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal manager,
                                 Model model, RedirectAttributes redirect) {
        CounterShift shift = shiftService.getDetailed(id);
        if (manager.getRole() != com.bbcinema.entity.enums.Role.MANAGER || !shift.isOpen()) {
            redirect.addFlashAttribute("error", "Ca #" + id + " không còn mở");
            return "redirect:/staff/shifts";
        }
        model.addAttribute("summary", shiftService.summary(shift));
        model.addAttribute("closeAction", "/staff/shifts/" + id + "/close");
        model.addAttribute("askManager", false); // the counter manager is the one signed in
        model.addAttribute("menu", "shifts-open");
        return "staff/shift-close";
    }

    @PostMapping("/staff/shifts/{id}/close")
    public String closeShift(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal manager,
                             @RequestParam(required = false) BigDecimal countedCash,
                             @RequestParam(required = false) String note,
                             RedirectAttributes redirect) {
        try {
            shiftService.closeAtCounter(id, manager.getId(), countedCash, note);
            redirect.addFlashAttribute("success", "Đã kết ca #" + id + ". Biên bản xem ở mục Ca bán vé.");
            return "redirect:/staff/shifts";
        } catch (BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/staff/shifts/" + id + "/close";
        }
    }

    /** Cancelling a paid ticket at the counter: the manager approves, the cash comes out of this shift. */
    @PostMapping("/staff/tickets/{code}/refund")
    public String refund(@PathVariable String code, @AuthenticationPrincipal AppUserPrincipal staff,
                         @RequestParam(required = false) String managerEmail,
                         @RequestParam(required = false) String managerPassword,
                         RedirectAttributes redirect) {
        try {
            Booking booking = shiftService.refund(staff.getId(), code, managerEmail, managerPassword);
            redirect.addFlashAttribute("success", "Đã huỷ vé " + booking.getCode() + ". Trả lại khách "
                    + money(booking.getTotalAmount()) + ".");
        } catch (BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            redirect.addFlashAttribute("error", StaffController.CONCURRENT_UPDATE);
        }
        return "redirect:/staff/checkin?code=" + code;
    }

    /** Spring Security lets this through only when the logout was refused: the shift is still open. */
    @PostMapping("/logout")
    public String logoutRefused(RedirectAttributes redirect) {
        redirect.addFlashAttribute("error", "Bạn đang trong ca bán vé. Gọi quản lý để kết ca, hệ thống sẽ tự đăng xuất.");
        return "redirect:" + CLOSE_PATH;
    }

    private static String money(BigDecimal amount) {
        return String.format(java.util.Locale.ROOT, "%,d", amount.longValue()).replace(',', '.') + "đ";
    }
}
