package com.bbcinema.web.admin;

import com.bbcinema.entity.CounterShift;
import com.bbcinema.service.ShiftService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.security.AppUserPrincipal;
import java.math.BigDecimal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Managers review the counter shifts: who worked, the opening float, the takings and the drawer count. */
@Controller
@RequestMapping("/admin/shifts")
@RequiredArgsConstructor
public class AdminShiftController {

    private final ShiftService shiftService;

    @GetMapping
    public String list(Model model) {
        List<CounterShift> shifts = shiftService.recent();
        // Shifts still open have no frozen totals yet: show their running figures.
        Map<Long, ShiftService.Summary> live = new LinkedHashMap<>();
        shifts.stream().filter(CounterShift::isOpen).forEach(s -> live.put(s.getId(), shiftService.summary(s)));
        model.addAttribute("shifts", shifts);
        model.addAttribute("live", live);
        model.addAttribute("menu", "shifts");
        return "admin/shifts/list";
    }

    /** Close an open shift from the admin area: the manager counts the drawer, no staff login needed. */
    @GetMapping("/{id}/close")
    public String closePage(@PathVariable Long id, Model model, RedirectAttributes redirect) {
        CounterShift shift = shiftService.getDetailed(id);
        if (!shift.isOpen()) {
            redirect.addFlashAttribute("error", "Ca #" + id + " đã kết");
            return "redirect:/admin/shifts";
        }
        model.addAttribute("summary", shiftService.summary(shift));
        model.addAttribute("closeAction", "/admin/shifts/" + id + "/close");
        model.addAttribute("askManager", false);
        model.addAttribute("menu", "shifts");
        return "staff/shift-close";
    }

    @PostMapping("/{id}/close")
    public String close(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal manager,
                        @RequestParam(required = false) BigDecimal countedCash,
                        @RequestParam(required = false) String note,
                        RedirectAttributes redirect) {
        try {
            shiftService.closeByManager(id, manager.getId(), countedCash, note);
            redirect.addFlashAttribute("success", "Đã kết ca #" + id + ". Bấm Biên bản để xem và in.");
            return "redirect:/admin/shifts";
        } catch (BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/shifts/" + id + "/close";
        }
    }

    @GetMapping("/{id}")
    public String report(@PathVariable Long id, Model model) {
        CounterShift shift = shiftService.getDetailed(id);
        if (shift.isOpen()) {
            model.addAttribute("summary", shiftService.summary(shift));
        }
        model.addAttribute("shift", shift);
        model.addAttribute("loggedOut", false);
        return "staff/shift-report";
    }
}
