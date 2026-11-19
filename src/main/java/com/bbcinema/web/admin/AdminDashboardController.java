package com.bbcinema.web.admin;

import com.bbcinema.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Clock;
import java.time.LocalDate;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final ReportService reportService;
    private final Clock clock;

    @GetMapping
    public String dashboard(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                            Model model) {
        LocalDate today = LocalDate.now(clock);
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusDays(13) : from;
        if (start.isAfter(end)) {
            start = end;
        }
        model.addAttribute("stats", reportService.dashboard());
        var daily = reportService.dailyRevenue(start, end);
        model.addAttribute("daily", daily);
        model.addAttribute("chartLabels", daily.stream().map(d -> String.format("%02d/%02d", d.date().getDayOfMonth(), d.date().getMonthValue())).toList());
        model.addAttribute("chartRevenues", daily.stream().map(d -> d.revenue().longValue()).toList());
        model.addAttribute("chartCounts", daily.stream().map(d -> d.bookings()).toList());
        model.addAttribute("byMovie", reportService.revenueByMovie(start, end));
        model.addAttribute("from", start);
        model.addAttribute("to", end);
        model.addAttribute("menu", "dashboard");
        return "admin/dashboard";
    }
}
