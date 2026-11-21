package com.bbcinema.web.admin;

import com.bbcinema.audit.Audited;
import com.bbcinema.dto.request.PromotionForm;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.service.PromotionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Clock;
import java.time.LocalDate;

@Controller
@RequestMapping("/admin/promotions")
@RequiredArgsConstructor
public class AdminPromotionController {

    private final PromotionService promotionService;
    private final Clock clock;

    @ModelAttribute("menu")
    public String menu() {
        return "promotions";
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("promotions", promotionService.findAll());
        model.addAttribute("today", LocalDate.now(clock));
        if (!model.containsAttribute("form")) {
            PromotionForm form = new PromotionForm();
            form.setValidFrom(LocalDate.now(clock));
            form.setValidTo(LocalDate.now(clock).plusMonths(1));
            model.addAttribute("form", form);
        }
        return "admin/promotions";
    }

    @GetMapping("/{id}/edit")
    public String edit(@PathVariable Long id, Model model) {
        model.addAttribute("form", promotionService.toForm(promotionService.getById(id)));
        return list(model);
    }

    @Audited("Lưu mã giảm giá")
    @PostMapping
    public String save(@Valid @ModelAttribute("form") PromotionForm form, BindingResult binding,
                       Model model, RedirectAttributes redirect) {
        if (binding.hasErrors()) {
            return list(model);
        }
        try {
            promotionService.save(form);
        } catch (BusinessException e) {
            binding.reject("promotion", e.getMessage());
            return list(model);
        }
        redirect.addFlashAttribute("success", "Đã lưu mã " + form.getCode().toUpperCase());
        return "redirect:/admin/promotions";
    }
}
