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
    private final com.bbcinema.service.BankTransferService bankTransferService;
    private final com.bbcinema.service.ShiftService shiftService;
    private final com.bbcinema.service.TicketPrintService ticketPrintService;
    private final Clock clock;

    private static final java.time.format.DateTimeFormatter PRINT_TIME =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");

    @GetMapping
    public String today(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                        @ModelAttribute("currentCinema") Cinema cinema,
                        @AuthenticationPrincipal AppUserPrincipal staff,
                        Model model) {
        if (staff.getRole() == com.bbcinema.entity.enums.Role.MANAGER) {
            return "redirect:/staff/shifts"; // counter managers do not sell; they look after the shifts
        }
        LocalDate today = LocalDate.now(clock);
        LocalDate selected = date == null ? today : date;
        model.addAttribute("today", today);
        model.addAttribute("dates", showtimeService.upcomingDates());
        model.addAttribute("selectedDate", selected);
        model.addAttribute("schedule", cinema == null ? List.of() : showtimeService.scheduleForCinema(cinema.getId(), selected));
        shiftService.findOpen(staff.getId()).ifPresent(shift -> model.addAttribute("shiftSummary", shiftService.summary(shift)));
        model.addAttribute("menu", "staff");
        return "staff/index";
    }

    @GetMapping("/sell/{showtimeId}")
    public String sellPage(@PathVariable Long showtimeId, Model model) {
        Showtime showtime = showtimeService.getDetailed(showtimeId);
        model.addAttribute("showtime", showtime);
        model.addAttribute("seatMap", showtimeService.seatMap(showtimeId));
        model.addAttribute("combos", comboService.findActive());
        model.addAttribute("bankQrEnabled", bankTransferService.isAvailable());
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
            if (booking.isPending()) { // bank transfer: wait for the money
                return "redirect:/staff/bank-transfer/" + booking.getCode();
            }
            redirect.addFlashAttribute("success", "Đã bán vé " + booking.getCode() + " (" + form.getPaymentMethod().getLabel() + ")");
            return "redirect:/staff/tickets/" + booking.getCode();
        } catch (BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/staff/sell/" + showtimeId;
        }
    }

    // ---------- bank transfer at the counter: the customer scans the QR on the counter screen

    @GetMapping("/bank-transfer/{code}")
    public String bankTransfer(@PathVariable String code, Model model) {
        Booking booking = bookingService.getByCode(code);
        if (booking.isPaid()) {
            return "redirect:/staff/tickets/" + code;
        }
        model.addAttribute("booking", booking);
        if (booking.isPending()) {
            model.addAttribute("transfer", bankTransferService.info(booking));
        }
        model.addAttribute("automatic", bankTransferService.isAutomatic());
        model.addAttribute("demoConfirm", bankTransferService.isDemoConfirmAllowed());
        model.addAttribute("menu", "staff");
        return "staff/bank-transfer";
    }

    @GetMapping(value = "/bank-transfer/{code}/qr.png", produces = org.springframework.http.MediaType.IMAGE_PNG_VALUE)
    @org.springframework.web.bind.annotation.ResponseBody
    public org.springframework.http.ResponseEntity<byte[]> bankQr(@PathVariable String code) {
        return org.springframework.http.ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(bankTransferService.qrPng(bookingService.getByCode(code)));
    }

    @GetMapping(value = "/bank-transfer/{code}/status", produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    @org.springframework.web.bind.annotation.ResponseBody
    public Map<String, String> bankTransferStatus(@PathVariable String code) {
        return Map.of("status", bookingService.getByCode(code).getStatus().name());
    }

    /** Staff saw the money arrive in the cinema's banking app. */
    @PostMapping("/bank-transfer/{code}/confirm")
    public String confirmTransfer(@PathVariable String code, @AuthenticationPrincipal AppUserPrincipal staff,
                                  RedirectAttributes redirect) {
        try {
            bankTransferService.confirmByStaff(code, staff.getFullName());
            redirect.addFlashAttribute("success", "Đã xác nhận chuyển khoản, vé " + code + " đã thanh toán");
            return "redirect:/staff/tickets/" + code;
        } catch (BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/staff/bank-transfer/" + code;
        }
    }

    @PostMapping("/bank-transfer/{code}/demo-confirm")
    public String demoConfirmTransfer(@PathVariable String code, RedirectAttributes redirect) {
        try {
            bankTransferService.demoConfirm(code);
            redirect.addFlashAttribute("success", "Ngân hàng (giả lập) đã báo có, vé " + code + " đã thanh toán");
            return "redirect:/staff/tickets/" + code;
        } catch (BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/staff/bank-transfer/" + code;
        }
    }

    /** The customer changed their mind: release the seats. */
    @PostMapping("/bank-transfer/{code}/cancel")
    public String cancelTransfer(@PathVariable String code, @AuthenticationPrincipal AppUserPrincipal staff,
                                 RedirectAttributes redirect) {
        Booking booking = bookingService.getByCode(code);
        try {
            bookingService.cancel(code, staff.getId(), staff.getRole());
            redirect.addFlashAttribute("success", "Đã huỷ đơn " + code + ", ghế được mở bán lại");
        } catch (BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/staff/sell/" + booking.getShowtime().getId();
    }

    @GetMapping("/tickets/{code}")
    public String ticket(@PathVariable String code, Model model) {
        Booking booking = bookingService.getByCode(code);
        model.addAttribute("booking", booking);
        model.addAttribute("payment", bookingService.paymentOf(booking));
        return "staff/ticket";
    }

    /** Called by the "In vé" button just before the browser prints: one click counts as one print. */
    @PostMapping("/tickets/{code}/printed")
    @ResponseBody
    public org.springframework.http.ResponseEntity<Map<String, Object>> ticketPrinted(@PathVariable String code) {
        try {
            Booking booking = ticketPrintService.recordPrint(code);
            return org.springframework.http.ResponseEntity.ok(Map.of(
                    "printCount", booking.getPrintCount(),
                    "lastPrintedAt", PRINT_TIME.format(booking.getLastPrintedAt())));
        } catch (NotFoundException e) {
            return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND)
                    .body(Map.of("message", e.getMessage()));
        } catch (BusinessException e) {
            return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT)
                    .body(Map.of("message", e.getMessage()));
        }
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
