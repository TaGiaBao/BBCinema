package com.bbcinema.api;

import com.bbcinema.dto.response.DailyRevenue;
import com.bbcinema.dto.response.MovieRevenue;
import com.bbcinema.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "Admin reports", description = "Thống kê doanh thu (chỉ ADMIN)")
@RestController
@RequestMapping("/api/admin/reports")
@RequiredArgsConstructor
public class AdminReportApiController {

    private final ReportService reportService;

    @Operation(summary = "Doanh thu theo ngày")
    @GetMapping("/revenue")
    public List<DailyRevenue> revenue(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reportService.dailyRevenue(from, to);
    }

    @Operation(summary = "Doanh thu theo phim")
    @GetMapping("/movies")
    public List<MovieRevenue> byMovie(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reportService.revenueByMovie(from, to);
    }
}
