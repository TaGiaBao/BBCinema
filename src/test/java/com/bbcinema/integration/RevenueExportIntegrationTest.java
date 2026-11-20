package com.bbcinema.integration;

import com.bbcinema.dto.response.DailyRevenue;
import com.bbcinema.dto.response.MovieRevenue;
import com.bbcinema.entity.Booking;
import com.bbcinema.entity.Showtime;
import com.bbcinema.entity.User;
import com.bbcinema.entity.enums.Role;
import com.bbcinema.service.ReportService;
import com.bbcinema.service.RevenueExportService;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RevenueExportIntegrationTest extends IntegrationTestBase {

    @Autowired ReportService reportService;
    @Autowired java.time.Clock clock;

    private Booking paidBooking(Showtime showtime, User buyer, int seats) {
        Booking booking = bookingService.holdSeats(buyer.getId(), showtime.getId(), freeSeats(showtime.getId(), seats));
        return bookingService.payWithMock(booking.getCode(), buyer.getId());
    }

    private Workbook download(User user, LocalDate from, LocalDate to) throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/admin/revenue/export")
                        .param("from", from.toString()).param("to", to.toString()).cookie(authCookie(user)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(RevenueExportService.CONTENT_TYPE))
                .andExpect(header().string("Content-Disposition",
                        containsString("doanh-thu_" + from + "_" + to + ".xlsx")))
                .andReturn().getResponse();
        return new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()));
    }

    /** Values of a column, from the row under the header down to the row before the total. */
    private static List<String> textColumn(Sheet sheet, int col) {
        List<String> values = new ArrayList<>();
        for (int r = 1; r < sheet.getLastRowNum(); r++) {
            values.add(sheet.getRow(r).getCell(col).getStringCellValue());
        }
        return values;
    }

    private static double totalRow(Sheet sheet, int col) {
        Row last = sheet.getRow(sheet.getLastRowNum());
        assertThat(last.getCell(0).getStringCellValue()).isEqualTo("Tổng cộng");
        return last.getCell(col).getNumericCellValue();
    }

    /** Value next to a label on the summary sheet. */
    private static double summaryValue(Sheet sheet, String label) {
        for (Row row : sheet) {
            Cell first = row.getCell(0);
            if (first != null && first.getCellType() == CellType.STRING && label.equals(first.getStringCellValue())) {
                return row.getCell(1).getNumericCellValue();
            }
        }
        throw new AssertionError("No summary row " + label);
    }

    @Test
    void exportMatchesTheReportAndLeavesRefundsOut() throws Exception {
        Showtime showtime = showtimeTomorrow();
        User buyer = member(25);
        Booking kept = paidBooking(showtime, buyer, 2);
        Booking refunded = paidBooking(showtime, buyer, 1);
        bookingService.cancel(refunded.getCode(), buyer.getId(), Role.USER);
        LocalDate today = LocalDate.now(clock);

        List<DailyRevenue> daily = reportService.dailyRevenue(today, today);
        List<MovieRevenue> byMovie = reportService.revenueByMovie(today, today);
        BigDecimal expectedTotal = daily.stream().map(DailyRevenue::revenue).reduce(BigDecimal.ZERO, BigDecimal::add);
        long expectedOrders = daily.stream().mapToLong(DailyRevenue::bookings).sum();

        try (Workbook wb = download(account(Role.ADMIN), today, today)) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(5);
            Sheet summary = wb.getSheet("Tổng quan");
            assertThat(summaryValue(summary, "Tổng doanh thu (đ)")).isEqualTo(expectedTotal.doubleValue());
            assertThat(summaryValue(summary, "Số đơn đã thanh toán")).isEqualTo(expectedOrders);
            assertThat(summaryValue(summary, "Số vé đã bán"))
                    .isEqualTo(byMovie.stream().mapToLong(MovieRevenue::tickets).sum());
            assertThat(summaryValue(summary, "Doanh thu bán trực tuyến (đ)") + summaryValue(summary, "Doanh thu bán tại quầy (đ)"))
                    .isEqualTo(expectedTotal.doubleValue());

            Sheet perDay = wb.getSheet("Theo ngày");
            assertThat(perDay.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Ngày");
            assertThat(perDay.getRow(0).getCell(0).getCellStyle().getFontIndex()).isNotZero();
            assertThat(perDay.getPaneInformation().isFreezePane()).isTrue();
            Cell dateCell = perDay.getRow(1).getCell(0);
            assertThat(DateUtil.isCellDateFormatted(dateCell)).isTrue();
            assertThat(dateCell.getLocalDateTimeCellValue().toLocalDate()).isEqualTo(today);
            assertThat(perDay.getRow(1).getCell(2).getCellStyle().getDataFormatString()).isEqualTo("#,##0");
            assertThat(totalRow(perDay, 2)).isEqualTo(expectedTotal.doubleValue());

            Sheet perMovie = wb.getSheet("Theo phim");
            assertThat(totalRow(perMovie, 2)).isEqualTo(expectedTotal.doubleValue());
            assertThat(textColumn(perMovie, 0)).contains(showtime.getMovie().getTitle());

            Sheet detail = wb.getSheet("Chi tiết đơn");
            assertThat(totalRow(detail, 12)).isEqualTo(expectedTotal.doubleValue());
            assertThat(detail.getLastRowNum() - 1).isEqualTo((int) expectedOrders);
            List<String> codes = textColumn(detail, 0);
            assertThat(codes).contains(kept.getCode()).doesNotContain(refunded.getCode());
            Row keptRow = detail.getRow(1 + codes.indexOf(kept.getCode()));
            assertThat(keptRow.getCell(2).getStringCellValue()).isEqualTo(showtime.getRoom().getCinema().getName());
            assertThat(keptRow.getCell(7).getStringCellValue()).isEqualTo("Thanh toán thử");
            assertThat(keptRow.getCell(8).getNumericCellValue()).isEqualTo(2);
            assertThat(keptRow.getCell(12).getNumericCellValue()).isEqualTo(kept.getTotalAmount().doubleValue());
            assertThat(DateUtil.isCellDateFormatted(keptRow.getCell(1))).isTrue();

            Sheet refunds = wb.getSheet("Đã hoàn tiền");
            List<String> refundCodes = textColumn(refunds, 0);
            assertThat(refundCodes).contains(refunded.getCode()).doesNotContain(kept.getCode());
        }
    }

    @Test
    void counterManagerMayExportButOthersMayNot() throws Exception {
        LocalDate today = LocalDate.now(clock);
        try (Workbook wb = download(account(Role.MANAGER), today.minusDays(3), today)) {
            assertThat(wb.getSheet("Theo ngày").getLastRowNum()).isEqualTo(5); // header, 4 days, total
        }
        mvc.perform(get("/admin/revenue/export").cookie(authCookie(member(20)))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/revenue/export").cookie(authCookie(account(Role.STAFF)))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/revenue/export"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/internal/login?redirect=%2Fadmin%2Frevenue%2Fexport"));
    }

    @Test
    void dashboardLinksToTheExportOfTheShownRange() throws Exception {
        mvc.perform(get("/admin").param("from", "2026-11-01").param("to", "2026-11-20").cookie(authCookie(account(Role.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/admin/revenue/export?from=2026-11-01&amp;to=2026-11-20")))
                .andExpect(content().string(containsString("Xuất Excel")));
    }
}
