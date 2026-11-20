package com.bbcinema.service;

import com.bbcinema.dto.response.DailyRevenue;
import com.bbcinema.dto.response.DashboardStats;
import com.bbcinema.dto.response.MovieRevenue;
import com.bbcinema.entity.Booking;
import com.bbcinema.entity.Payment;
import com.bbcinema.entity.Showtime;
import com.bbcinema.entity.enums.BookingStatus;
import com.bbcinema.entity.enums.PaymentStatus;
import com.bbcinema.repository.BookingRepository;
import com.bbcinema.repository.PaymentRepository;
import com.bbcinema.repository.ShowtimeSeatRepository;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds the Excel (.xlsx) copy of the revenue report shown on the admin dashboard. The figures come from
 * {@link ReportService}, so the file shows exactly what the page shows: only bookings still paid count as
 * revenue; refunded orders are listed on their own sheet and are not added to the totals.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RevenueExportService {

    public static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** IN lists are split into chunks of this size to stay well under database limits. */
    private static final int ID_CHUNK = 500;

    private final ReportService reportService;
    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final ShowtimeSeatRepository showtimeSeatRepository;
    private final Clock clock;

    /** File name of the export for this date range, e.g. doanh-thu_2026-11-01_2026-11-20.xlsx */
    public static String fileName(LocalDate from, LocalDate to) {
        return "doanh-thu_" + from + "_" + to + ".xlsx";
    }

    /** The whole workbook as bytes, for the date range [from, to] (both days included). */
    public byte[] export(LocalDate from, LocalDate to) {
        DashboardStats stats = reportService.dashboard();
        List<DailyRevenue> daily = reportService.dailyRevenue(from, to);
        List<MovieRevenue> byMovie = reportService.revenueByMovie(from, to);
        List<Booking> bookings = bookingRepository.findPaidOrRefundedBetween(from.atStartOfDay(), to.plusDays(1).atStartOfDay());
        List<Booking> paid = bookings.stream().filter(b -> b.getStatus() == BookingStatus.PAID).toList();
        List<Booking> refunded = bookings.stream().filter(b -> b.getStatus() == BookingStatus.CANCELLED).toList();
        List<Long> ids = bookings.stream().map(Booking::getId).toList();
        Map<Long, List<Payment>> payments = paymentsByBooking(ids);
        Map<Long, Long> seatCounts = seatCounts(paid.stream().map(Booking::getId).toList());

        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles st = new Styles(wb);
            summarySheet(wb, st, from, to, stats, daily, byMovie, paid, refunded);
            dailySheet(wb, st, daily);
            movieSheet(wb, st, byMovie);
            detailSheet(wb, st, paid, payments, seatCounts);
            refundSheet(wb, st, refunded, payments);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not build the revenue workbook", e);
        }
    }

    // ---------- sheets

    private void summarySheet(Workbook wb, Styles st, LocalDate from, LocalDate to, DashboardStats stats,
                              List<DailyRevenue> daily, List<MovieRevenue> byMovie,
                              List<Booking> paid, List<Booking> refunded) {
        Sheet sheet = wb.createSheet("Tổng quan");
        int r = 0;
        Row title = sheet.createRow(r++);
        cell(title, 0, "Báo cáo doanh thu BBCinema", st.title);
        r++;
        r = dateRow(sheet, r, "Từ ngày", from, st);
        r = dateRow(sheet, r, "Đến ngày", to, st);
        Row exported = sheet.createRow(r++);
        cell(exported, 0, "Thời điểm xuất", st.label);
        dateTimeCell(exported, 1, LocalDateTime.now(clock), st);
        r++;

        Row head = sheet.createRow(r++);
        cell(head, 0, "Trong khoảng đã chọn", st.header);
        cell(head, 1, "Giá trị", st.header);
        BigDecimal total = daily.stream().map(DailyRevenue::revenue).reduce(BigDecimal.ZERO, BigDecimal::add);
        long orders = daily.stream().mapToLong(DailyRevenue::bookings).sum();
        long tickets = byMovie.stream().mapToLong(MovieRevenue::tickets).sum();
        r = moneyRow(sheet, r, "Tổng doanh thu (đ)", total, st);
        r = numberRow(sheet, r, "Số đơn đã thanh toán", orders, st);
        r = numberRow(sheet, r, "Số vé đã bán", tickets, st);
        BigDecimal online = sumByChannel(paid, com.bbcinema.entity.enums.SalesChannel.ONLINE);
        BigDecimal counter = sumByChannel(paid, com.bbcinema.entity.enums.SalesChannel.COUNTER);
        r = moneyRow(sheet, r, "Doanh thu bán trực tuyến (đ)", online, st);
        r = moneyRow(sheet, r, "Doanh thu bán tại quầy (đ)", counter, st);
        r = numberRow(sheet, r, "Số đơn đã hoàn tiền (không tính vào doanh thu)", refunded.size(), st);
        r = moneyRow(sheet, r, "Số tiền đã hoàn (đ)", sum(refunded), st);
        r++;

        Row head2 = sheet.createRow(r++);
        cell(head2, 0, "Số liệu chung (như trên trang thống kê)", st.header);
        cell(head2, 1, "Giá trị", st.header);
        r = moneyRow(sheet, r, "Doanh thu hôm nay (đ)", stats.revenueToday(), st);
        r = numberRow(sheet, r, "Số đơn hôm nay", stats.bookingsToday(), st);
        r = moneyRow(sheet, r, "Doanh thu tháng này (đ)", stats.revenueThisMonth(), st);
        r = numberRow(sheet, r, "Số đơn tháng này", stats.bookingsThisMonth(), st);
        r = numberRow(sheet, r, "Thành viên", stats.members(), st);
        numberRow(sheet, r, "Phim đang chiếu", stats.moviesShowing(), st);
        sheet.setColumnWidth(0, 48 * 256);
        sheet.setColumnWidth(1, 22 * 256);
    }

    private void dailySheet(Workbook wb, Styles st, List<DailyRevenue> daily) {
        Sheet sheet = wb.createSheet("Theo ngày");
        header(sheet, st, "Ngày", "Số đơn", "Doanh thu (đ)");
        int r = 1;
        for (DailyRevenue d : daily) {
            Row row = sheet.createRow(r++);
            dateCell(row, 0, d.date(), st);
            numberCell(row, 1, d.bookings(), st.integer);
            moneyCell(row, 2, d.revenue(), st.money);
        }
        Row total = sheet.createRow(r);
        cell(total, 0, "Tổng cộng", st.totalLabel);
        numberCell(total, 1, daily.stream().mapToLong(DailyRevenue::bookings).sum(), st.totalInteger);
        moneyCell(total, 2, daily.stream().map(DailyRevenue::revenue).reduce(BigDecimal.ZERO, BigDecimal::add), st.totalMoney);
        widths(sheet, 14, 12, 18);
    }

    private void movieSheet(Workbook wb, Styles st, List<MovieRevenue> byMovie) {
        Sheet sheet = wb.createSheet("Theo phim");
        header(sheet, st, "Phim", "Số vé", "Doanh thu (đ)");
        int r = 1;
        for (MovieRevenue m : byMovie) {
            Row row = sheet.createRow(r++);
            cell(row, 0, m.movieTitle(), null);
            numberCell(row, 1, m.tickets(), st.integer);
            moneyCell(row, 2, m.revenue(), st.money);
        }
        Row total = sheet.createRow(r);
        cell(total, 0, "Tổng cộng", st.totalLabel);
        numberCell(total, 1, byMovie.stream().mapToLong(MovieRevenue::tickets).sum(), st.totalInteger);
        moneyCell(total, 2, byMovie.stream().map(MovieRevenue::revenue).reduce(BigDecimal.ZERO, BigDecimal::add), st.totalMoney);
        widths(sheet, 40, 10, 18);
    }

    private void detailSheet(Workbook wb, Styles st, List<Booking> paid, Map<Long, List<Payment>> payments,
                             Map<Long, Long> seatCounts) {
        Sheet sheet = wb.createSheet("Chi tiết đơn");
        header(sheet, st, "Mã đơn", "Thanh toán lúc", "Rạp", "Phòng", "Phim", "Suất chiếu", "Kênh bán",
                "Phương thức", "Số ghế", "Tiền vé (đ)", "Combo (đ)", "Giảm giá (đ)", "Thành tiền (đ)", "Trạng thái");
        int r = 1;
        for (Booking b : paid) {
            Showtime s = b.getShowtime();
            Row row = sheet.createRow(r++);
            cell(row, 0, b.getCode(), null);
            dateTimeCell(row, 1, b.getPaidAt(), st);
            cell(row, 2, s.getRoom().getCinema().getName(), null);
            cell(row, 3, s.getRoom().getName(), null);
            cell(row, 4, s.getMovie().getTitle(), null);
            dateTimeCell(row, 5, s.getStartTime(), st);
            cell(row, 6, b.getChannel().getLabel(), null);
            cell(row, 7, methods(payments.get(b.getId()), PaymentStatus.SUCCESS), null);
            numberCell(row, 8, seatCounts.getOrDefault(b.getId(), 0L), st.integer);
            moneyCell(row, 9, b.getTicketAmount(), st.money);
            moneyCell(row, 10, b.getComboAmount(), st.money);
            moneyCell(row, 11, b.getDiscountAmount(), st.money);
            moneyCell(row, 12, b.getTotalAmount(), st.money);
            cell(row, 13, b.getStatus().getLabel(), null);
        }
        Row total = sheet.createRow(r);
        cell(total, 0, "Tổng cộng", st.totalLabel);
        moneyCell(total, 12, sum(paid), st.totalMoney);
        widths(sheet, 12, 18, 26, 14, 32, 18, 12, 18, 8, 14, 14, 14, 16, 16);
    }

    private void refundSheet(Workbook wb, Styles st, List<Booking> refunded, Map<Long, List<Payment>> payments) {
        Sheet sheet = wb.createSheet("Đã hoàn tiền");
        header(sheet, st, "Mã đơn", "Thanh toán lúc", "Huỷ / hoàn lúc", "Rạp", "Phim", "Suất chiếu", "Kênh bán",
                "Phương thức", "Số tiền hoàn (đ)", "Trạng thái");
        int r = 1;
        for (Booking b : refunded) {
            Showtime s = b.getShowtime();
            Row row = sheet.createRow(r++);
            cell(row, 0, b.getCode(), null);
            dateTimeCell(row, 1, b.getPaidAt(), st);
            dateTimeCell(row, 2, b.getCancelledAt(), st);
            cell(row, 3, s.getRoom().getCinema().getName(), null);
            cell(row, 4, s.getMovie().getTitle(), null);
            dateTimeCell(row, 5, s.getStartTime(), st);
            cell(row, 6, b.getChannel().getLabel(), null);
            cell(row, 7, methods(payments.get(b.getId()), PaymentStatus.REFUNDED), null);
            moneyCell(row, 8, b.getTotalAmount(), st.money);
            cell(row, 9, PaymentStatus.REFUNDED.getLabel(), null);
        }
        Row total = sheet.createRow(r);
        cell(total, 0, "Tổng cộng", st.totalLabel);
        moneyCell(total, 8, sum(refunded), st.totalMoney);
        widths(sheet, 12, 18, 18, 26, 32, 18, 12, 18, 16, 16);
    }

    // ---------- data helpers

    private Map<Long, List<Payment>> paymentsByBooking(List<Long> ids) {
        Map<Long, List<Payment>> result = new HashMap<>();
        for (List<Long> chunk : chunks(ids)) {
            for (Payment p : paymentRepository.findSettledByBookingIds(chunk)) {
                result.computeIfAbsent(p.getBooking().getId(), k -> new ArrayList<>()).add(p);
            }
        }
        return result;
    }

    private Map<Long, Long> seatCounts(List<Long> ids) {
        Map<Long, Long> result = new HashMap<>();
        for (List<Long> chunk : chunks(ids)) {
            for (Object[] row : showtimeSeatRepository.countSeatsByBooking(chunk)) {
                result.put((Long) row[0], ((Number) row[1]).longValue());
            }
        }
        return result;
    }

    private static List<List<Long>> chunks(List<Long> ids) {
        List<List<Long>> chunks = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += ID_CHUNK) {
            chunks.add(ids.subList(i, Math.min(ids.size(), i + ID_CHUNK)));
        }
        return chunks;
    }

    /** Payment methods of a booking with the given status, e.g. "Tiền mặt"; empty when none is recorded. */
    private static String methods(List<Payment> payments, PaymentStatus status) {
        if (payments == null) {
            return "";
        }
        return payments.stream()
                .filter(p -> p.getStatus() == status)
                .map(p -> p.getMethod().getLabel())
                .distinct()
                .collect(Collectors.joining(", "));
    }

    private static BigDecimal sum(List<Booking> bookings) {
        return bookings.stream().map(Booking::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal sumByChannel(List<Booking> bookings, com.bbcinema.entity.enums.SalesChannel channel) {
        return sum(bookings.stream().filter(b -> b.getChannel() == channel).toList());
    }

    // ---------- cell helpers

    private static void header(Sheet sheet, Styles st, String... titles) {
        Row row = sheet.createRow(0);
        for (int i = 0; i < titles.length; i++) {
            cell(row, i, titles[i], st.header);
        }
        sheet.createFreezePane(0, 1);
    }

    private static void widths(Sheet sheet, int... chars) {
        for (int i = 0; i < chars.length; i++) {
            sheet.setColumnWidth(i, chars[i] * 256);
        }
    }

    private static void cell(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value == null ? "" : value);
        if (style != null) {
            cell.setCellStyle(style);
        }
    }

    private static void numberCell(Row row, int col, long value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static void moneyCell(Row row, int col, BigDecimal value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value == null ? 0 : value.doubleValue());
        cell.setCellStyle(style);
    }

    private static void dateCell(Row row, int col, LocalDate value, Styles st) {
        Cell cell = row.createCell(col);
        if (value != null) {
            cell.setCellValue(value);
            cell.setCellStyle(st.date);
        }
    }

    private static void dateTimeCell(Row row, int col, LocalDateTime value, Styles st) {
        Cell cell = row.createCell(col);
        if (value != null) {
            cell.setCellValue(value);
            cell.setCellStyle(st.dateTime);
        }
    }

    private static int dateRow(Sheet sheet, int r, String label, LocalDate value, Styles st) {
        Row row = sheet.createRow(r);
        cell(row, 0, label, st.label);
        dateCell(row, 1, value, st);
        return r + 1;
    }

    private static int moneyRow(Sheet sheet, int r, String label, BigDecimal value, Styles st) {
        Row row = sheet.createRow(r);
        cell(row, 0, label, null);
        moneyCell(row, 1, value, st.money);
        return r + 1;
    }

    private static int numberRow(Sheet sheet, int r, String label, long value, Styles st) {
        Row row = sheet.createRow(r);
        cell(row, 0, label, null);
        numberCell(row, 1, value, st.integer);
        return r + 1;
    }

    /** Cell styles shared by all sheets (a workbook may only hold a limited number of styles). */
    private static final class Styles {
        final CellStyle title;
        final CellStyle label;
        final CellStyle header;
        final CellStyle money;
        final CellStyle integer;
        final CellStyle date;
        final CellStyle dateTime;
        final CellStyle totalLabel;
        final CellStyle totalMoney;
        final CellStyle totalInteger;

        Styles(Workbook wb) {
            DataFormat format = wb.createDataFormat();
            Font bold = wb.createFont();
            bold.setBold(true);
            Font big = wb.createFont();
            big.setBold(true);
            big.setFontHeightInPoints((short) 14);

            title = wb.createCellStyle();
            title.setFont(big);
            label = wb.createCellStyle();
            label.setFont(bold);
            header = wb.createCellStyle();
            header.setFont(bold);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setBorderBottom(BorderStyle.THIN);
            money = wb.createCellStyle();
            money.setDataFormat(format.getFormat("#,##0"));
            integer = wb.createCellStyle();
            integer.setDataFormat(format.getFormat("#,##0"));
            date = wb.createCellStyle();
            date.setDataFormat(format.getFormat("dd/mm/yyyy"));
            dateTime = wb.createCellStyle();
            dateTime.setDataFormat(format.getFormat("dd/mm/yyyy hh:mm"));
            totalLabel = wb.createCellStyle();
            totalLabel.setFont(bold);
            totalLabel.setBorderTop(BorderStyle.THIN);
            totalMoney = wb.createCellStyle();
            totalMoney.cloneStyleFrom(money);
            totalMoney.setFont(bold);
            totalMoney.setBorderTop(BorderStyle.THIN);
            totalInteger = wb.createCellStyle();
            totalInteger.cloneStyleFrom(integer);
            totalInteger.setFont(bold);
            totalInteger.setBorderTop(BorderStyle.THIN);
        }
    }
}
