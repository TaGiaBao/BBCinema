package com.bbcinema.api;

import com.bbcinema.dto.request.LoginRequest;
import com.bbcinema.dto.request.RegisterForm;
import com.bbcinema.dto.response.TokenResponse;
import com.bbcinema.entity.User;
import com.bbcinema.security.AppUserPrincipal;
import com.bbcinema.security.JwtService;
import com.bbcinema.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Auth", description = "Đăng ký, đăng nhập lấy JWT")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthApiController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UserService userService;
    private final com.bbcinema.service.EmailVerificationService emailVerificationService;

    @Operation(summary = "Đăng nhập, trả về access token")
    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email().trim(), request.password()));
        AppUserPrincipal user = (AppUserPrincipal) auth.getPrincipal();
        if (!userService.getById(user.getId()).isEmailVerified()) {
            throw new com.bbcinema.exception.BusinessException(
                    "Email chưa được xác nhận. Hãy bấm đường dẫn trong email đã gửi khi đăng ký.");
        }
        return new TokenResponse(jwtService.generateToken(user), "Bearer", jwtService.getLifetimeSeconds(),
                user.getEmail(), user.getRole().name());
    }

    /** Answer to a sign-up: the account works after the member opens the link mailed to them. */
    public record RegisterResponse(String email, String message) {
    }

    @Operation(summary = "Đăng ký thành viên mới (cần xác nhận email trước khi đăng nhập)")
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@Valid @RequestBody RegisterForm form) {
        User user = userService.register(form);
        emailVerificationService.sendLink(user.getId());
        return new RegisterResponse(user.getEmail(), "Đã gửi đường dẫn xác nhận tới email. Xác nhận xong mới đăng nhập được.");
    }
}
