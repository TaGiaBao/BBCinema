package com.bbcinema.web.admin;

import com.bbcinema.dto.request.ComboForm;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.service.ComboService;
import com.bbcinema.service.PricingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/** Ticket price table and snack combos. */
@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminPricingController {

    private final PricingService pricingService;
    private final ComboService comboService;

    @GetMapping("/prices")
    public String prices(Model model) {
        model.addAttribute("prices", pricingService.findAll());
        model.addAttribute("menu", "prices");
        return "admin/prices";
    }

    @PostMapping("/prices")
    public String savePrices(@RequestParam Map<String, String> params, RedirectAttributes redirect) {
        Map<Long, BigDecimal> prices = new HashMap<>();
        try {
            params.forEach((key, value) -> {
                if (key.startsWith("price_")) {
                    prices.put(Long.parseLong(key.substring(6)), new BigDecimal(value.trim().replace(".", "")));
                }
            });
            pricingService.updatePrices(prices);
            redirect.addFlashAttribute("success", "Đã lưu bảng giá. Giá mới áp dụng cho các suất chiếu tạo sau.");
        } catch (NumberFormatException e) {
            redirect.addFlashAttribute("error", "Giá vé phải là số");
        } catch (BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/prices";
    }

    @GetMapping("/combos")
    public String combos(Model model) {
        model.addAttribute("combos", comboService.findAll());
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new ComboForm());
        }
        model.addAttribute("menu", "combos");
        return "admin/combos";
    }

    @GetMapping("/combos/{id}/edit")
    public String editCombo(@PathVariable Long id, Model model) {
        model.addAttribute("form", comboService.toForm(comboService.getById(id)));
        return combos(model);
    }

    @PostMapping("/combos")
    public String saveCombo(@Valid @ModelAttribute("form") ComboForm form, BindingResult binding,
                            @RequestParam(name = "image", required = false) MultipartFile image,
                            Model model, RedirectAttributes redirect) {
        if (binding.hasErrors()) {
            return combos(model);
        }
        try {
            comboService.save(form, image);
        } catch (BusinessException e) {
            binding.reject("combo", e.getMessage());
            return combos(model);
        }
        redirect.addFlashAttribute("success", "Đã lưu combo " + form.getName());
        return "redirect:/admin/combos";
    }
}
