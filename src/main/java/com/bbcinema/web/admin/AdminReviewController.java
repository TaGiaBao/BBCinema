package com.bbcinema.web.admin;

import com.bbcinema.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Lets admins hide reviews that break the rules (they then no longer count in the average). */
@Controller
@RequestMapping("/admin/reviews")
@RequiredArgsConstructor
public class AdminReviewController {

    private final ReviewService reviewService;

    @GetMapping
    public String list(@RequestParam(defaultValue = "0") int page, Model model) {
        model.addAttribute("page", reviewService.forAdmin(PageRequest.of(Math.max(0, page), 20)));
        model.addAttribute("menu", "reviews");
        return "admin/reviews";
    }

    @PostMapping("/{id}/hidden")
    public String setHidden(@PathVariable Long id, @RequestParam boolean hidden,
                            @RequestParam(defaultValue = "0") int page, RedirectAttributes redirect) {
        reviewService.setHidden(id, hidden);
        redirect.addFlashAttribute("success", hidden ? "Đã ẩn đánh giá" : "Đã hiện lại đánh giá");
        return "redirect:/admin/reviews?page=" + page;
    }
}
