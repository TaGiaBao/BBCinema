package com.bbcinema.integration;

import com.bbcinema.dto.response.DailyRevenue;
import com.bbcinema.dto.response.MovieRevenue;
import com.bbcinema.entity.Booking;
import com.bbcinema.entity.Payment;
import com.bbcinema.entity.Showtime;
import com.bbcinema.entity.User;
import com.bbcinema.entity.enums.PaymentMethod;
import com.bbcinema.entity.enums.Role;
import com.bbcinema.entity.enums.SalesChannel;
import com.bbcinema.repository.PaymentRepository;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RevenueExportIntegrationTest extends IntegrationTestBase {

    @Autowired ReportService reportService;
    @Autowired java.time.Clock clock;
    @Autowired PaymentRepository paymentRepository;

    /**
     * A paid booking whose amounts, channel, method and payment time are then set by hand, so the expected sheet
     * values are known without asking ReportService.
     */
    private Booking handMade(Showtime showtime, User buyer, int seats, SalesChannel channel, PaymentMethod method,
                             long ticket, long combo, long promotionDiscount, LocalDateTime paidAt) {
        Booking booking = paidBooking(showtime, buyer, seats);
        booking = bookingRepository.findById(booking.getId()).orElseThrow();
        booking.setChannel(channel);
        booking.setTicketAmount(BigDecimal.valueOf(ticket));
        booking.setComboAmount(BigDecimal.valueOf(combo));
        booking.setTierDiscount(BigDecimal.ZERO);
        booking.setPointsUsed(0);
        booking.setPromotionDiscount(BigDecimal.valueOf(promotionDiscount));
        booking.setTotalAmount(BigDecimal.valueOf(ticket + combo - promotionDiscount));
        booking.setPaidAt(paidAt);
        booking = bookingRepository.save(booking);
        for (Payment p : paymentRepository.findByBookingIdOrderByCreatedAtDesc(booking.getId())) {
            p.setMethod(method);
            p.setAmount(booking.getTotalAmount());
            p.setPaidAt(paidAt);
            paymentRepository.save(p);
        }
        return booking;
    }

    /** Refunds a booking for real, then moves the refund to the given time. */
    private void refundAt(Booking booking, User buyer, LocalDateTime refundedAt) {
        bookingService.cancel(booking.getCode(), buyer.getId(), Role.USER);
        Booking cancelled = bookingRepository.findById(booking.getId()).orElseThrow();
        cancelled.setCancelledAt(refundedAt);
        bookingRepository.save(cancelled);
        for (Payment p : paymentRepository.findByBookingIdOrderByCreatedAtDesc(booking.getId())) {
            p.setRefundedAt(refundedAt);
            paymentRepository.save(p);
        }
    }

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

    @Test
    void exportFiguresMatchAHandMadeFixture() throws Exception {
        Showtime showtime = showtimeTomorrow();
        User buyer = member(30);
        LocalDate from = LocalDate.of(2025, 2, 10);
        LocalDate to = LocalDate.of(2025, 2, 12);
        // Online: 2 seats 180,000 + combo 65,000 - promotion 24,500 = 220,500.
        Booking online = handMade(showtime, buyer, 2, SalesChannel.ONLINE, PaymentMethod.BANK_QR,
                180_000, 65_000, 24_500, LocalDateTime.of(2025, 2, 10, 10, 0));
        // Counter: 1 seat 75,000 in cash.
        Booking counter = handMade(showtime, buyer, 1, SalesChannel.COUNTER, PaymentMethod.CASH,
                75_000, 0, 0, LocalDateTime.of(2025, 2, 11, 20, 30));
        // Paid before the range, refunded inside it: on the refund sheet, never in the revenue.
        Booking refundedInside = handMade(showtime, buyer, 1, SalesChannel.ONLINE, PaymentMethod.BANK_QR,
                100_000, 0, 0, LocalDateTime.of(2025, 2, 1, 10, 0));
        refundAt(refundedInside, buyer, LocalDateTime.of(2025, 2, 11, 9, 0));
        // Paid inside the range, refunded after it: no revenue (no longer paid, as on the page) and no refund here.
        Booking refundedAfter = handMade(showtime, buyer, 1, SalesChannel.ONLINE, PaymentMethod.BANK_QR,
                50_000, 0, 0, LocalDateTime.of(2025, 2, 10, 15, 0));
        refundAt(refundedAfter, buyer, LocalDateTime.of(2025, 2, 20, 9, 0));

        try (Workbook wb = download(account(Role.ADMIN), from, to)) {
            Sheet summary = wb.getSheet("Tổng quan");
            assertThat(summaryValue(summary, "Tổng doanh thu (đ)")).isEqualTo(295_500);
            assertThat(summaryValue(summary, "Số đơn đã thanh toán")).isEqualTo(2);
            assertThat(summaryValue(summary, "Số vé đã bán")).isEqualTo(3);
            assertThat(summaryValue(summary, "Doanh thu bán trực tuyến (đ)")).isEqualTo(220_500);
            assertThat(summaryValue(summary, "Doanh thu bán tại quầy (đ)")).isEqualTo(75_000);
            assertThat(summaryValue(summary, "Đơn hoàn tiền trong kỳ (không tính vào doanh thu)")).isEqualTo(1);
            assertThat(summaryValue(summary, "Số tiền đã hoàn trong kỳ (đ)")).isEqualTo(100_000);

            Sheet perDay = wb.getSheet("Theo ngày");
            assertThat(perDay.getLastRowNum()).isEqualTo(4); // header, 3 days, total
            assertThat(perDay.getRow(1).getCell(1).getNumericCellValue()).isEqualTo(1);
            assertThat(perDay.getRow(1).getCell(2).getNumericCellValue()).isEqualTo(220_500);
            assertThat(perDay.getRow(2).getCell(1).getNumericCellValue()).isEqualTo(1);
            assertThat(perDay.getRow(2).getCell(2).getNumericCellValue()).isEqualTo(75_000);
            assertThat(perDay.getRow(3).getCell(1).getNumericCellValue()).isEqualTo(0);
            assertThat(perDay.getRow(3).getCell(2).getNumericCellValue()).isEqualTo(0);
            assertThat(totalRow(perDay, 1)).isEqualTo(2);
            assertThat(totalRow(perDay, 2)).isEqualTo(295_500);

            Sheet perMovie = wb.getSheet("Theo phim");
            assertThat(perMovie.getLastRowNum()).isEqualTo(2);
            assertThat(perMovie.getRow(1).getCell(0).getStringCellValue()).isEqualTo(showtime.getMovie().getTitle());
            assertThat(perMovie.getRow(1).getCell(1).getNumericCellValue()).isEqualTo(3);
            assertThat(perMovie.getRow(1).getCell(2).getNumericCellValue()).isEqualTo(295_500);

            Sheet detail = wb.getSheet("Chi tiết đơn");
            assertThat(textColumn(detail, 0)).containsExactly(online.getCode(), counter.getCode());
            Row first = detail.getRow(1);
            assertThat(first.getCell(1).getLocalDateTimeCellValue()).isEqualTo(LocalDateTime.of(2025, 2, 10, 10, 0));
            assertThat(first.getCell(6).getStringCellValue()).isEqualTo("Trực tuyến");
            assertThat(first.getCell(7).getStringCellValue()).isEqualTo("Chuyển khoản QR");
            assertThat(first.getCell(8).getNumericCellValue()).isEqualTo(2);
            assertThat(first.getCell(9).getNumericCellValue()).isEqualTo(180_000);
            assertThat(first.getCell(10).getNumericCellValue()).isEqualTo(65_000);
            assertThat(first.getCell(11).getNumericCellValue()).isEqualTo(24_500);
            assertThat(first.getCell(12).getNumericCellValue()).isEqualTo(220_500);
            Row second = detail.getRow(2);
            assertThat(second.getCell(6).getStringCellValue()).isEqualTo("Tại quầy");
            assertThat(second.getCell(7).getStringCellValue()).isEqualTo("Tiền mặt");
            assertThat(second.getCell(8).getNumericCellValue()).isEqualTo(1);
            assertThat(second.getCell(12).getNumericCellValue()).isEqualTo(75_000);
            assertThat(totalRow(detail, 12)).isEqualTo(295_500);

            Sheet refunds = wb.getSheet("Đã hoàn tiền");
            assertThat(refunds.getRow(0).getCell(2).getStringCellValue()).isEqualTo("Hoàn lúc");
            assertThat(textColumn(refunds, 0)).containsExactly(refundedInside.getCode());
            Row refund = refunds.getRow(1);
            assertThat(refund.getCell(1).getLocalDateTimeCellValue()).isEqualTo(LocalDateTime.of(2025, 2, 1, 10, 0));
            assertThat(refund.getCell(2).getLocalDateTimeCellValue()).isEqualTo(LocalDateTime.of(2025, 2, 11, 9, 0));
            assertThat(refund.getCell(8).getNumericCellValue()).isEqualTo(100_000);
            assertThat(totalRow(refunds, 8)).isEqualTo(100_000);
        }
    }

    @Test
    void rangesLongerThan366DaysAreRefused() throws Exception {
        User admin = account(Role.ADMIN);
        // Exactly 366 days (2024 is a leap year) is fine.
        download(admin, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31)).close();
        mvc.perform(get("/admin/revenue/export").param("from", "2024-01-01").param("to", "2025-01-01").cookie(authCookie(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Khoảng thời gian tối đa 366 ngày")));
        mvc.perform(get("/admin/revenue/export").param("from", "2024-02-01").param("to", "2024-01-01").cookie(authCookie(admin)))
                .andExpect(status().isBadRequest());
        // No end date means today: a start years ago is too long as well.
        mvc.perform(get("/admin/revenue/export").param("from", "2000-01-01").cookie(authCookie(admin)))
                .andExpect(status().isBadRequest());

        LocalDate today = LocalDate.now(clock);
        mvc.perform(get("/admin").param("from", "2000-01-01").param("to", "2025-01-01").cookie(authCookie(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Khoảng thời gian tối đa 366 ngày")))
                // Falls back to the default range: the last 14 days.
                .andExpect(content().string(containsString("value=\"" + today.minusDays(13) + "\"")))
                .andExpect(content().string(containsString("value=\"" + today + "\"")));
        mvc.perform(get("/admin").param("from", "2024-02-01").param("to", "2024-01-01").cookie(authCookie(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ngày bắt đầu phải trước hoặc bằng ngày kết thúc")));
    }
}
