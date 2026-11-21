package com.bbcinema.web;

import com.bbcinema.dto.request.RegisterForm;
import com.bbcinema.entity.enums.Role;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.exception.LoginBlockedException;
import com.bbcinema.security.AppUserPrincipal;
import com.bbcinema.security.ClientIp;
import com.bbcinema.security.LoginAttemptService;
import com.bbcinema.security.TokenCookies;
import com.bbcinema.service.AuditService;
import com.bbcinema.service.GoogleLoginService;
import com.bbcinema.service.PasswordResetService;
import com.bbcinema.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Login and registration pages. A successful login stores the JWT in an HttpOnly cookie.
 * Members sign in at /login; staff and admins use the separate internal login page.
 */
@Controller
@RequiredArgsConstructor
public class AuthController {

    private static final String WRONG_CREDENTIALS = "Email hoặc mật khẩu không đúng";

    private final AuthenticationManager authenticationManager;
    private final UserService userService;
    private final PasswordResetService passwordResetService;
    private final com.bbcinema.service.EmailVerificationService emailVerificationService;
    private final TokenCookies tokenCookies;
    private final LoginAttemptService loginAttempts;
    private final ClientIp clientIp;
    private final AuditService auditService;
    private final org.springframework.beans.factory.ObjectProvider<
            org.springframework.security.oauth2.client.registration.ClientRegistrationRepository> googleClients;

    @ModelAttribute("googleEnabled")
    public boolean googleEnabled() {
        return googleClients.getIfAvailable() != null;
    }

    public static final String INTERNAL_LOGIN = "/internal/login";

    @GetMapping("/login")
    public String loginPage(@RequestParam(required = false) String redirect,
                            @RequestParam(required = false) String google,
                            @ModelAttribute("currentUser") AppUserPrincipal currentUser,
                            Model model) {
        if (currentUser != null) {
            return "redirect:/";
        }
        if (google != null) {
            model.addAttribute("error", switch (google) {
                case GoogleLoginService.UNVERIFIED -> "Email Google của bạn chưa được xác minh.";
                case GoogleLoginService.INTERNAL -> "Vui lòng dùng trang đăng nhập nội bộ.";
                case GoogleLoginService.LOCKED -> "Tài khoản của bạn đã bị khoá. Vui lòng liên hệ rạp.";
                default -> "Không đăng nhập được bằng Google. Vui lòng thử lại.";
            });
        }
        model.addAttribute("redirect", SafeRedirect.localPath(redirect, ""));
        return "auth/login";
    }

    /** Customer login: only members (USER) may sign in here. */
    @PostMapping("/login")
    public String login(@RequestParam String email,
                        @RequestParam String password,
                        @RequestParam(required = false) String redirect,
                        HttpServletRequest request,
                        HttpServletResponse response,
                        Model model) {
        LoginResult result = signIn(email, password, false, request, response);
        if (result.user() != null) {
            return "redirect:" + SafeRedirect.localPath(redirect, "/");
        }
        model.addAttribute("error", result.error());
        model.addAttribute("email", email);
        model.addAttribute("unverified", result.unverified());
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
                                HttpServletRequest request,
                                HttpServletResponse response,
                                Model model) {
        LoginResult result = signIn(email, password, true, request, response);
        AppUserPrincipal user = result.user();
        // Every attempt at the internal door is logged, the failed ones too (guessing, a customer trying it).
        auditService.record(new AuditService.Entry(user == null ? null : user.getId(),
                user == null ? email : user.getEmail(), user == null ? null : user.getRole(), "Đăng nhập nội bộ",
                null, result.error(), user != null, request.getMethod(), request.getRequestURI(), clientIp.of(request)));
        if (user != null) {
            return "redirect:" + SafeRedirect.localPath(redirect, homeFor(user.getRole()));
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
        com.bbcinema.entity.User user;
        try {
            user = userService.register(form);
        } catch (BusinessException e) {
            binding.reject("register", e.getMessage());
            return "auth/register";
        }
        // The account works once the member opens the link mailed to them: tickets will be sent to this address.
        emailVerificationService.sendLink(user.getId());
        redirect.addFlashAttribute("email", user.getEmail());
        return "redirect:/register/check-email";
    }

    @GetMapping("/register/check-email")
    public String checkEmail() {
        return "auth/check-email";
    }

    @GetMapping("/verify-email")
    public String verifyEmail(@RequestParam(required = false) String token, RedirectAttributes redirect) {
        try {
            String email = emailVerificationService.verify(token);
            redirect.addFlashAttribute("success", "Đã xác nhận email " + email + ". Bạn có thể đăng nhập.");
            redirect.addFlashAttribute("email", email);
        } catch (BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/login";
    }

    @PostMapping("/verify-email/resend")
    public String resendVerification(@RequestParam String email, RedirectAttributes redirect) {
        emailVerificationService.resend(email);
        // Same answer whether or not the address has an unconfirmed account.
        redirect.addFlashAttribute("email", email.trim());
        redirect.addFlashAttribute("resent", true);
        return "redirect:/register/check-email";
    }

    private record LoginResult(AppUserPrincipal user, String error, boolean unverified) {
        LoginResult(AppUserPrincipal user, String error) {
            this(user, error, false);
        }
    }

    /**
     * Checks the password, then that the account belongs to the area it signs in to.
     * The cookie is written only when both checks pass. After too many failures for the account or the client
     * address, every attempt is refused for a while without even checking the password; the attempt is reserved
     * before the check, so parallel requests cannot get more guesses than the limit.
     */
    private LoginResult signIn(String email, String password, boolean internalArea,
                               HttpServletRequest request, HttpServletResponse response) {
        LoginAttemptService.Attempt attempt;
        try {
            attempt = loginAttempts.begin(email, clientIp.of(request));
        } catch (LoginBlockedException e) {
            return new LoginResult(null, LoginAttemptService.BLOCKED_MESSAGE);
        } catch (AuthenticationException e) {
            return new LoginResult(null, WRONG_CREDENTIALS);
        }
        AppUserPrincipal user;
        try {
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email.trim(), password));
            if (!attempt.succeeded()) {
                return new LoginResult(null, LoginAttemptService.BLOCKED_MESSAGE);
            }
            user = (AppUserPrincipal) auth.getPrincipal();
        } catch (AuthenticationException e) {
            if (attempt.failed()) {
                return new LoginResult(null, LoginAttemptService.BLOCKED_MESSAGE);
            }
            return new LoginResult(null, e instanceof DisabledException
                    ? "Tài khoản của bạn đã bị khoá. Vui lòng liên hệ rạp."
                    : WRONG_CREDENTIALS);
        } finally {
            attempt.close();
        }
        if (internalArea && !isInternal(user.getRole())) {
            return new LoginResult(null, "Trang này chỉ dành cho nhân viên và quản trị viên. "
                    + "Thành viên vui lòng đăng nhập tại trang chủ.");
        }
        if (!internalArea && isInternal(user.getRole())) {
            return new LoginResult(null, "Vui lòng dùng trang đăng nhập nội bộ.");
        }
        if (!userService.getById(user.getId()).isEmailVerified()) {
            return new LoginResult(null, "Email này chưa được xác nhận. Hãy bấm đường dẫn trong email chúng tôi đã gửi, "
                    + "hoặc gửi lại email xác nhận.", true);
        }
        tokenCookies.signIn(response, user);
        return new LoginResult(user, null);
    }

    private static boolean isInternal(Role role) {
        return role.isInternal();
    }

    private static String homeFor(Role role) {
        return switch (role) {
            case ADMIN, MANAGER -> "/admin";
            case STAFF -> "/staff";
            default -> "/";
        };
    }
}
