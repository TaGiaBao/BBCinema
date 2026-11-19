package com.bbcinema.web;

import com.bbcinema.service.VnPayService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;

/** Pages that VNPay calls after the customer pays. */
@Controller
@RequestMapping("/payment")
@RequiredArgsConstructor
public class PaymentController {

    private final VnPayService vnPayService;

    /** The customer's browser comes back here from VNPay. */
    @GetMapping("/vnpay-return")
    public String vnpayReturn(@RequestParam Map<String, String> params, RedirectAttributes redirect) {
        VnPayService.VnPayResult result;
        try {
            result = vnPayService.handleCallback(params);
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/member/bookings";
        }
        redirect.addFlashAttribute(result.success() ? "success" : "error", result.message());
        if (result.bookingCode() == null) {
            return "redirect:/member/bookings";
        }
        return "redirect:/booking/" + result.bookingCode() + (result.success() ? "" : "/checkout");
    }

    /** Server-to-server notification from VNPay (IPN). The reply format is defined by VNPay. */
    @GetMapping("/vnpay-ipn")
    @ResponseBody
    public Map<String, String> vnpayIpn(@RequestParam Map<String, String> params) {
        try {
            VnPayService.VnPayResult result = vnPayService.handleCallback(params);
            if (!result.validSignature()) {
                return Map.of("RspCode", "97", "Message", "Invalid signature");
            }
            if (result.bookingCode() == null) {
                return Map.of("RspCode", "01", "Message", "Order not found");
            }
            return Map.of("RspCode", "00", "Message", "Confirm Success");
        } catch (RuntimeException e) {
            return Map.of("RspCode", "99", "Message", "Unknown error");
        }
    }
}
