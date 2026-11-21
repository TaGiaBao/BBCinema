package com.bbcinema.web.admin;

import com.bbcinema.dto.request.BirthdayRewardForm;
import com.bbcinema.entity.enums.DiscountType;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.service.BirthdayRewardService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Clock;
import java.time.LocalDate;

/** "Quà sinh nhật thành viên": the gift settings and this year's codes. General manager only, like promotions. */
@Controller
@RequestMapping("/admin/promotions/birthday")
@RequiredArgsConstructor
public class AdminBirthdayRewardController {

    private final BirthdayRewardService birthdayRewardService;
    private final Clock clock;

    @ModelAttribute("menu")
    public String menu() {
        return "promotions";
    }

    @GetMapping
    public String page(Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", birthdayRewardService.toForm(birthdayRewardService.settings()));
        }
        model.addAttribute("settings", birthdayRewardService.settings());
        model.addAttribute("discountTypes", DiscountType.values());
        model.addAttribute("rewards", birthdayRewardService.latestThisYear());
        model.addAttribute("rewardCount", birthdayRewardService.countThisYear());
        model.addAttribute("today", LocalDate.now(clock));
        return "admin/birthday-reward";
    }

    @PostMapping
    public String save(@Valid @ModelAttribute("form") BirthdayRewardForm form, BindingResult binding,
                       Model model, RedirectAttributes redirect) {
        if (binding.hasErrors()) {
            return page(model);
        }
        try {
            birthdayRewardService.saveSettings(form);
        } catch (BusinessException e) {
            binding.reject("birthday", e.getMessage());
            return page(model);
        }
        String message = "Đã lưu cài đặt quà sinh nhật";
        if (form.isEnabled()) {
            // Members whose birthday month is already running get their code now, not tomorrow morning.
            int issued = birthdayRewardService.issueDue();
            if (issued > 0) {
                message += ". Đã gửi mã cho " + issued + " thành viên có sinh nhật trong tháng này";
            }
        }
        redirect.addFlashAttribute("success", message);
        return "redirect:/admin/promotions/birthday";
    }
}
