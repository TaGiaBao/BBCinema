package com.bbcinema.web;

import com.bbcinema.entity.Booking;
import com.bbcinema.entity.Showtime;
import com.bbcinema.entity.enums.PaymentMethod;
import com.bbcinema.entity.enums.Role;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.security.AppUserPrincipal;
import com.bbcinema.service.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Online booking pages: choose seats -> choose combos -> checkout -> ticket.
 * All business rules live in {@link BookingService}; this class only moves data between pages.
 */
@Controller
@RequestMapping("/booking")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;
    private final ShowtimeService showtimeService;
    private final ComboService comboService;
    private final VnPayService vnPayService;
    private final com.bbcinema.service.BankTransferService bankTransferService;
    private final QrCodeService qrCodeService;
    private final MovieService movieService;
    private final Clock clock;

    // ---------- step 1: seats

    @GetMapping("/showtime/{showtimeId}")
    public String seats(@PathVariable Long showtimeId, Model model) {
        Showtime showtime = showtimeService.getDetailed(showtimeId);
        if (showtime.isCancelled() || !showtime.getStartTime().isAfter(LocalDateTime.now(clock))) {
            throw new BusinessException("Suất chiếu này không còn nhận đặt vé trực tuyến");
        }
        model.addAttribute("showtime", showtime);
        model.addAttribute("seatMap", showtimeService.seatMap(showtimeId));
        model.addAttribute("otherShowtimes", showtimeService.findForMovie(showtime.getMovie().getId(),
                showtime.getRoom().getCinema().getId(), showtime.getStartTime().toLocalDate()));
        return "booking/seats";
    }

    @PostMapping("/showtime/{showtimeId}")
    public String hold(@PathVariable Long showtimeId,
                       @RequestParam(name = "seatIds", required = false) List<Long> seatIds,
                       @AuthenticationPrincipal AppUserPrincipal user,
                       RedirectAttributes redirect) {
        try {
            Booking booking = bookingService.holdSeats(user.getId(), showtimeId, seatIds);
            return "redirect:/booking/" + booking.getCode() + "/combos";
        } catch (BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/booking/showtime/" + showtimeId;
        }
    }

    // ---------- step 2: combos

    @GetMapping("/{code}/combos")
    public String combos(@PathVariable String code, @AuthenticationPrincipal AppUserPrincipal user, Model model) {
        Booking booking = bookingService.getForViewer(code, user.getId(), Role.USER);
        if (!booking.isPending()) {
            return "redirect:/booking/" + code;
        }
        Map<Long, Integer> selected = new HashMap<>();
        booking.getCombos().forEach(c -> selected.put(c.getCombo().getId(), c.getQuantity()));
        model.addAttribute("booking", booking);
        model.addAttribute("combos", comboService.findActive());
        model.addAttribute("selected", selected);
        return "booking/combos";
    }

    @PostMapping("/{code}/combos")
    public String saveCombos(@PathVariable String code,
                             @RequestParam Map<String, String> params,
                             @AuthenticationPrincipal AppUserPrincipal user,
                             RedirectAttributes redirect) {
        try {
            bookingService.updateCombos(code, user.getId(), comboQuantitiesOf(params));
            return "redirect:/booking/" + code + "/checkout";
        } catch (BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/booking/" + code + "/combos";
        }
    }

    /** Reads form fields named combo_<id> into a map of combo id -> quantity. */
    public static Map<Long, Integer> comboQuantitiesOf(Map<String, String> params) {
        Map<Long, Integer> quantities = new HashMap<>();
        params.forEach((key, value) -> {
            if (key.startsWith("combo_")) {
                try {
                    quantities.put(Long.parseLong(key.substring(6)), Integer.parseInt(value.trim()));
                } catch (NumberFormatException e) {
                    throw new BusinessException("Số lượng combo không hợp lệ");
                }
            }
        });
        return quantities;
    }

    // ---------- step 3: checkout

    @GetMapping("/{code}/checkout")
    public String checkout(@PathVariable String code, @AuthenticationPrincipal AppUserPrincipal user, Model model) {
        Booking booking = bookingService.getForViewer(code, user.getId(), Role.USER);
        if (!booking.isPending()) {
            return "redirect:/booking/" + code;
        }
        model.addAttribute("booking", booking);
        model.addAttribute("maxPoints", bookingService.maxRedeemablePoints(booking));
        model.addAttribute("vnpayEnabled", vnPayService.isEnabled());
        model.addAttribute("bankQrEnabled", bankTransferService.isAvailable());
        model.addAttribute("mockAllowed", mockAllowed());
        return "booking/checkout";
    }

    @PostMapping("/{code}/promotion")
    public String applyPromotion(@PathVariable String code, @RequestParam String promotionCode,
                                 @AuthenticationPrincipal AppUserPrincipal user, RedirectAttributes redirect) {
        try {
            Booking booking = bookingService.applyPromotion(code, user.getId(), promotionCode);
            redirect.addFlashAttribute("success", "Đã áp dụng mã " + booking.getPromotion().getCode());
        } catch (BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/booking/" + code + "/checkout";
    }

    @PostMapping("/{code}/promotion/remove")
    public String removePromotion(@PathVariable String code, @AuthenticationPrincipal AppUserPrincipal user,
                                  RedirectAttributes redirect) {
        try {
            bookingService.removePromotion(code, user.getId());
        } catch (BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/booking/" + code + "/checkout";
    }

    @PostMapping("/{code}/points")
    public String usePoints(@PathVariable String code, @RequestParam(defaultValue = "0") int points,
                            @AuthenticationPrincipal AppUserPrincipal user, RedirectAttributes redirect) {
        try {
            bookingService.usePoints(code, user.getId(), points);
            if (points > 0) {
                redirect.addFlashAttribute("success", "Đã dùng " + points + " điểm");
            }
        } catch (BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/booking/" + code + "/checkout";
    }

    @PostMapping("/{code}/pay")
    public String pay(@PathVariable String code,
                      @RequestParam(defaultValue = "BANK_QR") PaymentMethod method,
                      @AuthenticationPrincipal AppUserPrincipal user,
                      HttpServletRequest request,
                      RedirectAttributes redirect) {
        try {
            if (method == PaymentMethod.VNPAY) {
                return "redirect:" + vnPayService.createPaymentUrl(code, user.getId(), clientIp(request));
            }
            if (method == PaymentMethod.BANK_QR && bankTransferService.isAvailable()) {
                bookingService.startBankTransfer(code, user.getId(), Role.USER);
                return "redirect:/booking/" + code + "/bank-transfer";
            }
            if (!mockAllowed()) {
                throw new BusinessException("Vui lòng chọn một hình thức thanh toán");
            }
            bookingService.payWithMock(code, user.getId());
            redirect.addFlashAttribute("success", "Thanh toán thành công! Chúc bạn xem phim vui vẻ.");
            return "redirect:/booking/" + code;
        } catch (BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/booking/" + code + "/checkout";
        }
    }

    // ---------- ticket, cancel, QR

    @GetMapping("/{code}")
    public String ticket(@PathVariable String code, @AuthenticationPrincipal AppUserPrincipal user, Model model) {
        Booking booking = bookingService.getForViewer(code, user.getId(), user.getRole());
        model.addAttribute("booking", booking);
        model.addAttribute("canCancel", bookingService.canCancel(booking));
        model.addAttribute("cancelDeadline", bookingService.cancelDeadline(booking));
        model.addAttribute("payments", bookingService.payments(booking.getId()));
        if (booking.isPaid()) {
            model.addAttribute("comingSoonPicks",
                    movieService.featuredComingSoon(booking.getShowtime().getMovie().getId()));
        }
        return "booking/ticket";
    }

    @PostMapping("/{code}/cancel")
    public String cancel(@PathVariable String code, @AuthenticationPrincipal AppUserPrincipal user,
                         RedirectAttributes redirect) {
        try {
            bookingService.cancel(code, user.getId(), user.getRole());
            redirect.addFlashAttribute("success", "Đã huỷ đơn " + code);
        } catch (BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/booking/" + code;
    }

    // ---------- bank transfer by VietQR

    @GetMapping("/{code}/bank-transfer")
    public String bankTransfer(@PathVariable String code, @AuthenticationPrincipal AppUserPrincipal user, Model model) {
        Booking booking = bookingService.getForViewer(code, user.getId(), Role.USER);
        if (!booking.isPending()) {
            return "redirect:/booking/" + code;
        }
        model.addAttribute("booking", booking);
        model.addAttribute("transfer", bankTransferService.info(booking));
        model.addAttribute("automatic", bankTransferService.isAutomatic());
        model.addAttribute("demoConfirm", bankTransferService.isDemoConfirmAllowed());
        return "booking/bank-transfer";
    }

    @GetMapping(value = "/{code}/bank-qr.png", produces = MediaType.IMAGE_PNG_VALUE)
    @ResponseBody
    public ResponseEntity<byte[]> bankQr(@PathVariable String code, @AuthenticationPrincipal AppUserPrincipal user) {
        Booking booking = bookingService.getForViewer(code, user.getId(), Role.USER);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_PNG)
                .body(bankTransferService.qrPng(booking));
    }

    /** Polled by the QR page every few seconds: PENDING, PAID, EXPIRED... */
    @GetMapping(value = "/{code}/payment-status", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, String> paymentStatus(@PathVariable String code, @AuthenticationPrincipal AppUserPrincipal user) {
        Booking booking = bookingService.getForViewer(code, user.getId(), user.getRole());
        return Map.of("status", booking.getStatus().name());
    }

    @PostMapping("/{code}/bank-transfer/demo-confirm")
    public String demoConfirm(@PathVariable String code, @AuthenticationPrincipal AppUserPrincipal user,
                              RedirectAttributes redirect) {
        bookingService.getForViewer(code, user.getId(), Role.USER);
        try {
            bankTransferService.demoConfirm(code);
            redirect.addFlashAttribute("success", "Thanh toán thành công! Chúc bạn xem phim vui vẻ.");
            return "redirect:/booking/" + code;
        } catch (BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/booking/" + code + "/bank-transfer";
        }
    }

    /** "Thanh toán thử" is offered only when the cinema has set up no real way to pay (fresh install, tests). */
    private boolean mockAllowed() {
        return !bankTransferService.isAvailable() && !vnPayService.isEnabled();
    }

    @GetMapping(value = "/{code}/qr.png", produces = MediaType.IMAGE_PNG_VALUE)
    @ResponseBody
    public ResponseEntity<byte[]> qr(@PathVariable String code, @AuthenticationPrincipal AppUserPrincipal user) {
        Booking booking = bookingService.getForViewer(code, user.getId(), user.getRole());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.IMAGE_PNG)
                .body(qrCodeService.png(booking.getCode(), 260));
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
