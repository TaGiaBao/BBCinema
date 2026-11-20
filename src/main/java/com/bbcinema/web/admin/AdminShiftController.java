package com.bbcinema.web.admin;

import com.bbcinema.entity.CounterShift;
import com.bbcinema.service.ShiftService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

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
