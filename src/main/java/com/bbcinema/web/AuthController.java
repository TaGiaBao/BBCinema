package com.bbcinema.web;

import com.bbcinema.config.AppProperties;
import com.bbcinema.dto.request.RegisterForm;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.security.AppUserPrincipal;
import com.bbcinema.security.JwtService;
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

/** Login and registration pages. A successful login stores the JWT in an HttpOnly cookie. */
@Controller
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UserService userService;
    private final AppProperties properties;

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

    @PostMapping("/login")
    public String login(@RequestParam String email,
                        @RequestParam String password,
                        @RequestParam(required = false) String redirect,
                        HttpServletResponse response,
                        Model model) {
        try {
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email.trim(), password));
            AppUserPrincipal user = (AppUserPrincipal) auth.getPrincipal();
            writeTokenCookie(response, jwtService.generateToken(user));
            return "redirect:" + SafeRedirect.localPath(redirect, homeFor(user));
        } catch (DisabledException e) {
            model.addAttribute("error", "Tài khoản của bạn đã bị khoá. Vui lòng liên hệ rạp.");
        } catch (AuthenticationException e) {
            model.addAttribute("error", "Email hoặc mật khẩu không đúng");
        }
        model.addAttribute("email", email);
        model.addAttribute("redirect", SafeRedirect.localPath(redirect, ""));
        return "auth/login";
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

    private void writeTokenCookie(HttpServletResponse response, String token) {
        ResponseCookie cookie = ResponseCookie.from(properties.jwt().cookieName(), token)
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofSeconds(jwtService.getLifetimeSeconds()))
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    private static String homeFor(AppUserPrincipal user) {
        return switch (user.getRole()) {
            case ADMIN -> "/admin";
            case STAFF -> "/staff";
            default -> "/";
        };
    }
}
