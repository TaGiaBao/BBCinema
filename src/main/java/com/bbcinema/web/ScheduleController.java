package com.bbcinema.web;

import com.bbcinema.entity.Cinema;
import com.bbcinema.service.ShowtimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/** "Lịch chiếu theo rạp": every movie playing in the chosen cinema on one day. */
@Controller
@RequiredArgsConstructor
public class ScheduleController {

    private final ShowtimeService showtimeService;
    private final Clock clock;

    @GetMapping("/schedule")
    public String schedule(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                           @ModelAttribute("currentCinema") Cinema currentCinema,
                           Model model) {
        LocalDate today = LocalDate.now(clock);
        LocalDate selected = date == null || date.isBefore(today) ? today : date;
        model.addAttribute("today", today);
        model.addAttribute("dates", showtimeService.upcomingDates());
        model.addAttribute("selectedDate", selected);
        model.addAttribute("schedule", currentCinema == null ? List.of()
                : showtimeService.scheduleForCinema(currentCinema.getId(), selected));
        return "schedule";
    }
}
