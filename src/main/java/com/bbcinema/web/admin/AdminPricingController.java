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
    private final com.bbcinema.service.InventoryService inventoryService;

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
        model.addAttribute("recipes", inventoryService.recipes());
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new ComboForm());
        }
        model.addAttribute("menu", "combos");
        return "admin/combos";
    }

    /** What one portion of a combo takes from the stock; selling it then takes this from the cinema's stock. */
    @GetMapping("/combos/{id}/recipe")
    public String recipe(@PathVariable Long id, Model model) {
        model.addAttribute("combo", comboService.getById(id));
        model.addAttribute("items", inventoryService.items());
        java.util.Map<Long, java.math.BigDecimal> current = new java.util.HashMap<>();
        inventoryService.recipes().getOrDefault(id, java.util.List.of())
                .forEach(i -> current.put(i.getItem().getId(), i.getQuantity().stripTrailingZeros()));
        model.addAttribute("current", current);
        model.addAttribute("menu", "combos");
        return "admin/combo-recipe";
    }

    @PostMapping("/combos/{id}/recipe")
    public String saveRecipe(@PathVariable Long id, @RequestParam java.util.Map<String, String> params, RedirectAttributes redirect) {
        java.util.Map<Long, java.math.BigDecimal> quantities = AdminInventoryController.quantities(params, "use_");
        if (quantities == null) {
            redirect.addFlashAttribute("error", "Định lượng phải là số, ví dụ 1 hoặc 0,15");
            return "redirect:/admin/combos/" + id + "/recipe";
        }
        try {
            inventoryService.saveRecipe(id, quantities);
            redirect.addFlashAttribute("success", "Đã lưu công thức combo " + comboService.getById(id).getName());
            return "redirect:/admin/combos";
        } catch (BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/combos/" + id + "/recipe";
        }
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
