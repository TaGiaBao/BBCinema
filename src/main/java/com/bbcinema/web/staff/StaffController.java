package com.bbcinema.web.staff;

import com.bbcinema.audit.Audited;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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
    private final com.bbcinema.service.MemberCodeService memberCodeService;
    private final com.bbcinema.service.AudienceTicketService audienceTicketService;
    private final Clock clock;

    /** Shown when another action (refund, payment...) changed the booking between loading and saving it. */
    static final String CONCURRENT_UPDATE = "Vé vừa được cập nhật bởi thao tác khác, vui lòng thử lại.";

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
        model.addAttribute("audienceOptions", audienceTicketService.optionsFor(showtime));
        model.addAttribute("bankQrEnabled", bankTransferService.isAvailable());
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new CounterSaleForm());
        }
        model.addAttribute("menu", "staff");
        return "staff/sell";
    }

    @Audited("Bán vé tại quầy")
    @PostMapping("/sell/{showtimeId}")
    public String sell(@PathVariable Long showtimeId,
                       @Valid @ModelAttribute("form") CounterSaleForm form,
                       BindingResult binding,
                       @RequestParam Map<String, String> params,
                       @AuthenticationPrincipal AppUserPrincipal staff,
                       RedirectAttributes redirect) {
        if (binding.hasErrors()) {
            redirect.addFlashAttribute("error", binding.getAllErrors().get(0).getDefaultMessage());
            keepMember(form, redirect);
            keepSeats(form, params, redirect);
            return "redirect:/staff/sell/" + showtimeId;
        }
        try {
            form.setComboQuantities(com.bbcinema.web.BookingController.comboQuantitiesOf(params));
            form.setSeatAudiences(audiencesOf(params));
            Booking booking = bookingService.sellAtCounter(staff.getId(), showtimeId, form);
            String note = tierInsteadOfAudience(form, booking)
                    ? ". Giảm hạng thành viên có lợi hơn nên vé được tính giá thường kèm giảm hạng." : "";
            if (booking.isPending()) { // bank transfer: wait for the money
                if (!note.isEmpty()) {
                    redirect.addFlashAttribute("success", note.substring(2));
                }
                return "redirect:/staff/bank-transfer/" + booking.getCode();
            }
            redirect.addFlashAttribute("success", "Đã bán vé " + booking.getCode() + " (" + form.getPaymentMethod().getLabel() + ")" + note);
            return "redirect:/staff/tickets/" + booking.getCode();
        } catch (BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            keepMember(form, redirect);
            keepSeats(form, params, redirect);
            return "redirect:/staff/sell/" + showtimeId;
        }
    }

    /**
     * After a failed sale (e.g. not enough cash) the page loads again: give back the seats and their ticket types,
     * so staff do not have to pick them again and an HSSV/senior ticket is not sold at the adult price by mistake.
     * Only seat ids and type names go back; the page shows seats someone else took in the meantime as taken.
     */
    static void keepSeats(CounterSaleForm form, Map<String, String> params, RedirectAttributes redirect) {
        if (form.getShowtimeSeatIds() == null || form.getShowtimeSeatIds().isEmpty()) {
            return;
        }
        Map<Long, com.bbcinema.entity.enums.AudienceType> types;
        try {
            types = audiencesOf(params);
        } catch (BusinessException e) {
            types = Map.of();
        }
        StringBuilder kept = new StringBuilder();
        for (Long id : form.getShowtimeSeatIds()) {
            if (id == null) {
                continue;
            }
            if (!kept.isEmpty()) {
                kept.append(',');
            }
            kept.append(id).append(':').append(types.getOrDefault(id, com.bbcinema.entity.enums.AudienceType.ADULT).name());
        }
        redirect.addFlashAttribute("keptSeats", kept.toString());
    }

    /** Reads the per-seat selects named audience_<showtime seat id> into seat id -> ticket type. */
    static Map<Long, com.bbcinema.entity.enums.AudienceType> audiencesOf(Map<String, String> params) {
        Map<Long, com.bbcinema.entity.enums.AudienceType> out = new java.util.HashMap<>();
        params.forEach((key, value) -> {
            if (key.startsWith("audience_") && value != null && !value.isBlank()) {
                try {
                    out.put(Long.parseLong(key.substring(9)), com.bbcinema.entity.enums.AudienceType.valueOf(value.trim()));
                } catch (IllegalArgumentException e) {
                    throw new BusinessException("Loại vé không hợp lệ");
                }
            }
        });
        return out;
    }

    /** Staff chose audience tickets but the member's tier discount was larger, so it was used instead. */
    private static boolean tierInsteadOfAudience(CounterSaleForm form, Booking booking) {
        boolean chosen = form.getSeatAudiences().values().stream().anyMatch(com.bbcinema.entity.enums.AudienceType::isDiscounted);
        return chosen && booking.getAudienceDiscount().signum() == 0 && booking.getTierDiscount().signum() > 0;
    }

    /**
     * After a failed sale the page loads again: give it back the member staff had attached (and whether they had
     * confirmed the name), so the next try is not charged at full price by mistake. The page looks the code up
     * again, so nothing here is trusted.
     */
    private static void keepMember(CounterSaleForm form, RedirectAttributes redirect) {
        if (form.getMemberCode() == null || form.getMemberCode().isBlank()) {
            return;
        }
        redirect.addFlashAttribute("keptMemberCode", form.getMemberCode().trim());
        redirect.addFlashAttribute("keptMemberConfirmed", form.isMemberConfirmed());
        redirect.addFlashAttribute("keptMemberMatchedBy",
                "PHONE".equals(form.getMemberMatchedBy()) ? "PHONE" : "CODE");
    }

    /**
     * "Khách thành viên" panel of the sell page: finds a member by member code (typed or scanned) or phone number.
     * POST, so the phone number stays out of URLs and access logs; the reply holds only what staff need.
     */
    @PostMapping(value = "/members/lookup", produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public org.springframework.http.ResponseEntity<?> lookupMember(@RequestParam(name = "q", required = false) String query,
                                                                   @RequestParam(required = false) Long showtimeId) {
        try {
            // The age that matters is the age on the day of the film, not today.
            LocalDate onDate = showtimeId == null ? null : showtimeService.getDetailed(showtimeId).getStartTime().toLocalDate();
            return org.springframework.http.ResponseEntity.ok(memberCodeService.lookupForCounter(query, onDate));
        } catch (NotFoundException e) {
            return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND)
                    .body(Map.of("message", e.getMessage()));
        } catch (BusinessException e) {
            return org.springframework.http.ResponseEntity.unprocessableEntity().body(Map.of("message", e.getMessage()));
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
    @Audited("Xác nhận chuyển khoản")
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

    @Audited("Xác nhận chuyển khoản (thử)")
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
    @Audited("Huỷ đơn chuyển khoản")
    @PostMapping("/bank-transfer/{code}/cancel")
    public String cancelTransfer(@PathVariable String code, @AuthenticationPrincipal AppUserPrincipal staff,
                                 RedirectAttributes redirect) {
        Booking booking = bookingService.getByCode(code);
        try {
            bookingService.cancel(code, staff.getId(), staff.getRole());
            redirect.addFlashAttribute("success", "Đã huỷ đơn " + code + ", ghế được mở bán lại");
        } catch (BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        } catch (ObjectOptimisticLockingFailureException e) {
            redirect.addFlashAttribute("error", CONCURRENT_UPDATE);
        }
        return "redirect:/staff/sell/" + booking.getShowtime().getId();
    }

    @GetMapping("/tickets/{code}")
    public String ticket(@PathVariable String code, @RequestParam(required = false) String from, Model model) {
        Booking booking = bookingService.getByCode(code);
        model.addAttribute("booking", booking);
        // Only a flag: the way back is always built from the booking code, never from the request.
        model.addAttribute("fromCheckin", "checkin".equals(from));
        model.addAttribute("payment", bookingService.paymentOf(booking));
        return "staff/ticket";
    }

    /** Called by the "In vé" button just before the browser prints: one click counts as one print. */
    @Audited("In vé")
    @PostMapping("/tickets/{code}/printed")
    @ResponseBody
    public org.springframework.http.ResponseEntity<Map<String, Object>> ticketPrinted(@PathVariable String code) {
        try {
            com.bbcinema.service.TicketPrintService.PrintLog log = ticketPrintService.recordPrint(code);
            return org.springframework.http.ResponseEntity.ok(Map.of(
                    "printCount", log.printCount(),
                    "lastPrintedAt", PRINT_TIME.format(log.lastPrintedAt())));
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

    @Audited("Soát vé")
    @PostMapping("/checkin")
    public String checkIn(@RequestParam String code, RedirectAttributes redirect) {
        String normalized = code.trim().toUpperCase();
        try {
            Booking booking = bookingService.checkIn(normalized);
            redirect.addFlashAttribute("success", "Soát vé thành công: " + booking.getCode() + " - "
                    + booking.getShowtime().getRoom().getName() + " - ghế " + booking.getSeatLabels());
        } catch (BusinessException | NotFoundException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        } catch (ObjectOptimisticLockingFailureException e) {
            redirect.addFlashAttribute("error", CONCURRENT_UPDATE);
        }
        return "redirect:/staff/checkin?code=" + normalized;
    }
}
