package com.bbcinema.web.admin;

import com.bbcinema.audit.Audited;
import com.bbcinema.service.ReportService;
import com.bbcinema.service.RevenueExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminDashboardController {

    /** Longest report, in days (both ends included): keeps one request from loading years of orders. */
    static final int MAX_DAYS = 366;
    static final String RANGE_TOO_LONG = "Khoảng thời gian tối đa 366 ngày";
    private static final int DEFAULT_DAYS = 14;

    private final ReportService reportService;
    private final RevenueExportService revenueExportService;
    private final Clock clock;

    @GetMapping
    public String dashboard(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                            Model model) {
        String invalid = rangeError(from, to);
        if (invalid != null) {
            model.addAttribute("error", invalid);
            from = null;
            to = null;
        }
        LocalDate[] range = range(from, to);
        LocalDate start = range[0];
        LocalDate end = range[1];
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

    /** The report on the page above as an Excel file, for the same date range. */
    @Audited("Xuất báo cáo doanh thu")
    @GetMapping("/revenue/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        String invalid = rangeError(from, to);
        if (invalid != null) {
            return ResponseEntity.badRequest()
                    .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8))
                    .body(invalid.getBytes(StandardCharsets.UTF_8));
        }
        LocalDate[] range = range(from, to);
        byte[] body = revenueExportService.export(range[0], range[1]);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(RevenueExportService.CONTENT_TYPE))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(RevenueExportService.fileName(range[0], range[1])).build().toString())
                .body(body);
    }

    /** Date range of the report: up to today, the last 14 days by default. Check it with {@link #rangeError} first. */
    private LocalDate[] range(LocalDate from, LocalDate to) {
        LocalDate end = to == null ? LocalDate.now(clock) : to;
        LocalDate start = from == null ? end.minusDays(DEFAULT_DAYS - 1) : from;
        return new LocalDate[]{start, end};
    }

    /** Why the requested range cannot be shown (start after end, or longer than 366 days), or null when it is fine. */
    private String rangeError(LocalDate from, LocalDate to) {
        LocalDate[] range = range(from, to);
        if (range[0].isAfter(range[1])) {
            return "Ngày bắt đầu phải trước hoặc bằng ngày kết thúc";
        }
        if (ChronoUnit.DAYS.between(range[0], range[1]) + 1 > MAX_DAYS) {
            return RANGE_TOO_LONG;
        }
        return null;
    }
}
