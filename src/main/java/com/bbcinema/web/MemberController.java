package com.bbcinema.web;

import com.bbcinema.dto.request.PasswordForm;
import com.bbcinema.dto.request.ProfileForm;
import com.bbcinema.entity.User;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.security.AppUserPrincipal;
import com.bbcinema.service.BookingService;
import com.bbcinema.service.PointService;
import com.bbcinema.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Member area: profile, password, ticket history and reward points. */
@Controller
@RequestMapping("/member")
@RequiredArgsConstructor
public class MemberController {

    private final UserService userService;
    private final BookingService bookingService;
    private final PointService pointService;
    private final com.bbcinema.service.MembershipService membershipService;

    @GetMapping
    public String profile(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        User user = userService.getById(principal.getId());
        addCommon(model, user, "profile");
        model.addAttribute("profileForm", userService.toProfileForm(user));
        model.addAttribute("passwordForm", new PasswordForm());
        return "member/profile";
    }

    @PostMapping("/profile")
    public String updateProfile(@AuthenticationPrincipal AppUserPrincipal principal,
                                @Valid @ModelAttribute("profileForm") ProfileForm form,
                                BindingResult binding,
                                Model model,
                                RedirectAttributes redirect) {
        if (binding.hasErrors()) {
            addCommon(model, userService.getById(principal.getId()), "profile");
            model.addAttribute("passwordForm", new PasswordForm());
            return "member/profile";
        }
        userService.updateProfile(principal.getId(), form);
        redirect.addFlashAttribute("success", "Đã cập nhật thông tin tài khoản");
        return "redirect:/member";
    }

    @PostMapping("/password")
    public String changePassword(@AuthenticationPrincipal AppUserPrincipal principal,
                                 @Valid @ModelAttribute("passwordForm") PasswordForm form,
                                 BindingResult binding,
                                 Model model,
                                 RedirectAttributes redirect) {
        if (!binding.hasErrors()) {
            try {
                userService.changePassword(principal.getId(), form);
                redirect.addFlashAttribute("success", "Đã đổi mật khẩu");
                return "redirect:/member";
            } catch (BusinessException e) {
                binding.reject("password", e.getMessage());
            }
        }
        User user = userService.getById(principal.getId());
        addCommon(model, user, "profile");
        model.addAttribute("profileForm", userService.toProfileForm(user));
        return "member/profile";
    }

    @GetMapping("/bookings")
    public String bookings(@AuthenticationPrincipal AppUserPrincipal principal,
                           @RequestParam(defaultValue = "0") int page,
                           Model model) {
        addCommon(model, userService.getById(principal.getId()), "bookings");
        model.addAttribute("page", bookingService.history(principal.getId(), PageRequest.of(Math.max(page, 0), 10)));
        return "member/bookings";
    }

    @GetMapping("/points")
    public String points(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        addCommon(model, userService.getById(principal.getId()), "points");
        model.addAttribute("transactions", pointService.history(principal.getId()));
        return "member/points";
    }

    private void addCommon(Model model, User user, String tab) {
        model.addAttribute("user", user);
        model.addAttribute("tab", tab);
        model.addAttribute("membership", membershipService.status(user));
    }
}
