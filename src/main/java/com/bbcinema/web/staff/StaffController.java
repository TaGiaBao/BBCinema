package com.bbcinema.web.staff;

import com.bbcinema.dto.request.CounterSaleForm;
import com.bbcinema.entity.Booking;
import com.bbcinema.entity.Cinema;
import com.bbcinema.entity.Showtime;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.exception.NotFoundException;
import com.bbcinema.security.AppUserPrincipal;
import com.bbcinema.service.BookingService;
import com.bbcinema.service.ComboService;
import com.bbcinema.service.ShowtimeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Counter staff: sell tickets to walk-in customers and check tickets at the door. */
@Controller
@RequestMapping("/staff")
@RequiredArgsConstructor
public class StaffController {

    private final ShowtimeService showtimeService;
    private final BookingService bookingService;
    private final ComboService comboService;
    private final Clock clock;

    @GetMapping
    public String today(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                        @ModelAttribute("currentCinema") Cinema cinema,
                        Model model) {
        LocalDate today = LocalDate.now(clock);
        LocalDate selected = date == null ? today : date;
        model.addAttribute("today", today);
        model.addAttribute("dates", showtimeService.upcomingDates());
        model.addAttribute("selectedDate", selected);
        model.addAttribute("schedule", cinema == null ? List.of() : showtimeService.scheduleForCinema(cinema.getId(), selected));
        model.addAttribute("menu", "staff");
        return "staff/index";
    }

    @GetMapping("/sell/{showtimeId}")
    public String sellPage(@PathVariable Long showtimeId, Model model) {
        Showtime showtime = showtimeService.getDetailed(showtimeId);
        model.addAttribute("showtime", showtime);
        model.addAttribute("seatMap", showtimeService.seatMap(showtimeId));
        model.addAttribute("combos", comboService.findActive());
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new CounterSaleForm());
        }
        model.addAttribute("menu", "staff");
        return "staff/sell";
    }

    @PostMapping("/sell/{showtimeId}")
    public String sell(@PathVariable Long showtimeId,
                       @Valid @ModelAttribute("form") CounterSaleForm form,
                       BindingResult binding,
                       @RequestParam Map<String, String> params,
                       @AuthenticationPrincipal AppUserPrincipal staff,
                       RedirectAttributes redirect) {
        if (binding.hasErrors()) {
            redirect.addFlashAttribute("error", binding.getAllErrors().get(0).getDefaultMessage());
            return "redirect:/staff/sell/" + showtimeId;
        }
        try {
            form.setComboQuantities(com.bbcinema.web.BookingController.comboQuantitiesOf(params));
            Booking booking = bookingService.sellAtCounter(staff.getId(), showtimeId, form);
            redirect.addFlashAttribute("success", "Đã bán vé " + booking.getCode());
            return "redirect:/staff/tickets/" + booking.getCode();
        } catch (BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/staff/sell/" + showtimeId;
        }
    }

    @GetMapping("/tickets/{code}")
    public String ticket(@PathVariable String code, Model model) {
        model.addAttribute("booking", bookingService.getByCode(code));
        return "staff/ticket";
    }

    @GetMapping("/checkin")
    public String checkInPage(@RequestParam(required = false) String code, Model model) {
        if (code != null && !code.isBlank()) {
            try {
                model.addAttribute("booking", bookingService.getByCode(code.trim().toUpperCase()));
            } catch (NotFoundException e) {
                model.addAttribute("lookupError", e.getMessage());
            }
            model.addAttribute("code", code.trim().toUpperCase());
        }
        model.addAttribute("menu", "checkin");
        return "staff/checkin";
    }

    @PostMapping("/checkin")
    public String checkIn(@RequestParam String code, RedirectAttributes redirect) {
        String normalized = code.trim().toUpperCase();
        try {
            Booking booking = bookingService.checkIn(normalized);
            redirect.addFlashAttribute("success", "Soát vé thành công: " + booking.getCode() + " - "
                    + booking.getShowtime().getRoom().getName() + " - ghế " + booking.getSeatLabels());
        } catch (BusinessException | NotFoundException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/staff/checkin?code=" + normalized;
    }
}
