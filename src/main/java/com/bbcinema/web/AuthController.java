package com.bbcinema.web;

import com.bbcinema.config.AppProperties;
import com.bbcinema.dto.request.RegisterForm;
import com.bbcinema.entity.enums.Role;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.security.AppUserPrincipal;
import com.bbcinema.security.JwtService;
import com.bbcinema.service.PasswordResetService;
import com.bbcinema.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Duration;

/**
 * Login and registration pages. A successful login stores the JWT in an HttpOnly cookie.
 * Members sign in at /login; staff and admins use the separate internal login page.
 */
@Controller
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UserService userService;
    private final AppProperties properties;
    private final PasswordResetService passwordResetService;

    public static final String INTERNAL_LOGIN = "/internal/login";

    @GetMapping("/login")
    public String loginPage(@RequestParam(required = false) String redirect,
                            @ModelAttribute("currentUser") AppUserPrincipal currentUser,
                            Model model) {
        if (currentUser != null) {
            return "redirect:/";
        }
        model.addAttribute("redirect", SafeRedirect.localPath(redirect, ""));
        return "auth/login";
    }

    /** Customer login: only members (USER) may sign in here. */
    @PostMapping("/login")
    public String login(@RequestParam String email,
                        @RequestParam String password,
                        @RequestParam(required = false) String redirect,
                        HttpServletResponse response,
                        Model model) {
        LoginResult result = signIn(email, password, false, response);
        if (result.user() != null) {
            return "redirect:" + SafeRedirect.localPath(redirect, "/");
        }
        model.addAttribute("error", result.error());
        model.addAttribute("email", email);
        model.addAttribute("redirect", SafeRedirect.localPath(redirect, ""));
        return "auth/login";
    }

    @GetMapping(INTERNAL_LOGIN)
    public String internalLoginPage(@RequestParam(required = false) String redirect,
                                    @ModelAttribute("currentUser") AppUserPrincipal currentUser,
                                    Model model) {
        if (currentUser != null && isInternal(currentUser.getRole())) {
            return "redirect:" + homeFor(currentUser.getRole());
        }
        model.addAttribute("redirect", SafeRedirect.localPath(redirect, ""));
        return "auth/internal-login";
    }

    /** Staff and admin login: customer accounts are refused. */
    @PostMapping(INTERNAL_LOGIN)
    public String internalLogin(@RequestParam String email,
                                @RequestParam String password,
                                @RequestParam(required = false) String redirect,
                                HttpServletResponse response,
                                Model model) {
        LoginResult result = signIn(email, password, true, response);
        if (result.user() != null) {
            return "redirect:" + SafeRedirect.localPath(redirect, homeFor(result.user().getRole()));
        }
        model.addAttribute("error", result.error());
        model.addAttribute("email", email);
        model.addAttribute("redirect", SafeRedirect.localPath(redirect, ""));
        return "auth/internal-login";
    }

    // ---------- forgot password

    @GetMapping("/forgot-password")
    public String forgotPasswordPage() {
        return "auth/forgot-password";
    }

    @PostMapping("/forgot-password")
    public String forgotPassword(@RequestParam String email, Model model) {
        passwordResetService.requestReset(email);
        // Same answer whether or not the address has an account.
        model.addAttribute("sent", true);
        model.addAttribute("email", email);
        return "auth/forgot-password";
    }

    @GetMapping("/reset-password")
    public String resetPasswordPage(@RequestParam(required = false) String token, Model model) {
        model.addAttribute("token", token);
        model.addAttribute("valid", passwordResetService.isValid(token));
        return "auth/reset-password";
    }

    @PostMapping("/reset-password")
    public String resetPassword(@RequestParam String token, @RequestParam String password,
                                @RequestParam String confirmPassword, Model model, RedirectAttributes redirect) {
        try {
            passwordResetService.resetPassword(token, password, confirmPassword);
        } catch (BusinessException e) {
            model.addAttribute("token", token);
            model.addAttribute("valid", passwordResetService.isValid(token));
            model.addAttribute("error", e.getMessage());
            return "auth/reset-password";
        }
        redirect.addFlashAttribute("success", "Đã đặt mật khẩu mới. Vui lòng đăng nhập lại.");
        return "redirect:/login";
    }

    @GetMapping("/register")
    public String registerPage(Model model) {
        model.addAttribute("form", new RegisterForm());
        return "auth/register";
    }

    @PostMapping("/register")
    public String register(@Valid @ModelAttribute("form") RegisterForm form,
                           BindingResult binding,
                           HttpServletRequest request,
                           HttpServletResponse response,
                           RedirectAttributes redirect) {
        if (binding.hasErrors()) {
            return "auth/register";
        }
        try {
            userService.register(form);
        } catch (BusinessException e) {
            binding.reject("register", e.getMessage());
            return "auth/register";
        }
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(form.getEmail().trim(), form.getPassword()));
        writeTokenCookie(response, jwtService.generateToken((AppUserPrincipal) auth.getPrincipal()));
        redirect.addFlashAttribute("success", "Chào mừng " + form.getFullName() + " đến với BBCinema!");
        return "redirect:/";
    }

    private record LoginResult(AppUserPrincipal user, String error) {
    }

    /**
     * Checks the password, then that the account belongs to the area it signs in to.
     * The cookie is written only when both checks pass.
     */
    private LoginResult signIn(String email, String password, boolean internalArea, HttpServletResponse response) {
        AppUserPrincipal user;
        try {
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email.trim(), password));
            user = (AppUserPrincipal) auth.getPrincipal();
        } catch (DisabledException e) {
            return new LoginResult(null, "Tài khoản của bạn đã bị khoá. Vui lòng liên hệ rạp.");
        } catch (AuthenticationException e) {
            return new LoginResult(null, "Email hoặc mật khẩu không đúng");
        }
        if (internalArea && !isInternal(user.getRole())) {
            return new LoginResult(null, "Trang này chỉ dành cho nhân viên và quản trị viên. "
                    + "Thành viên vui lòng đăng nhập tại trang chủ.");
        }
        if (!internalArea && isInternal(user.getRole())) {
            return new LoginResult(null, "Vui lòng dùng trang đăng nhập nội bộ.");
        }
        writeTokenCookie(response, jwtService.generateToken(user));
        return new LoginResult(user, null);
    }

    private static boolean isInternal(Role role) {
        return role == Role.STAFF || role == Role.ADMIN;
    }

    private void writeTokenCookie(HttpServletResponse response, String token) {
        ResponseCookie cookie = ResponseCookie.from(properties.jwt().cookieName(), token)
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofSeconds(jwtService.getLifetimeSeconds()))
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    private static String homeFor(Role role) {
        return switch (role) {
            case ADMIN -> "/admin";
            case STAFF -> "/staff";
            default -> "/";
        };
    }
}
