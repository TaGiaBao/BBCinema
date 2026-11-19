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
    private final QrCodeService qrCodeService;
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
                      @RequestParam(defaultValue = "MOCK") PaymentMethod method,
                      @AuthenticationPrincipal AppUserPrincipal user,
                      HttpServletRequest request,
                      RedirectAttributes redirect) {
        try {
            if (method == PaymentMethod.VNPAY) {
                return "redirect:" + vnPayService.createPaymentUrl(code, user.getId(), clientIp(request));
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
