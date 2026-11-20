package com.bbcinema.service;

import com.bbcinema.entity.User;
import com.bbcinema.entity.enums.Role;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.repository.UserRepository;
import com.bbcinema.security.AppUserPrincipal;
import com.bbcinema.security.CustomUserDetailsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Signs a member in with the Google account Google vouched for. The e-mail must be verified by Google.
 * A new address becomes a new member (with a random password they can reset later); an existing
 * member signs in to their own account. Staff and admin accounts must use the internal login page.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleLoginService {

    public static final String UNVERIFIED = "unverified";
    public static final String INTERNAL = "internal";
    public static final String LOCKED = "locked";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final UserService userService;
    private final CustomUserDetailsService userDetailsService;

    /** Thrown with one of the codes above; the login page turns the code into a message. */
    public static class GoogleLoginException extends BusinessException {
        private final String code;

        public GoogleLoginException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    @Transactional
    public AppUserPrincipal signIn(String email, boolean emailVerified, String name) {
        if (email == null || email.isBlank() || !emailVerified) {
            throw new GoogleLoginException(UNVERIFIED, "Email Google chưa được xác minh");
        }
        String address = email.trim().toLowerCase();
        User user = userRepository.findByEmailIgnoreCase(address).orElse(null);
        if (user == null) {
            byte[] raw = new byte[24];
            RANDOM.nextBytes(raw);
            String fullName = name == null || name.isBlank() ? address.substring(0, address.indexOf('@')) : name.trim();
            user = userService.createAccount(address, fullName, Base64.getEncoder().encodeToString(raw), Role.USER);
            log.info("New member {} created from Google sign-in", address);
        }
        if (user.getRole() != Role.USER) {
            throw new GoogleLoginException(INTERNAL, "Tài khoản nội bộ vui lòng dùng trang đăng nhập nội bộ");
        }
        if (!user.isEnabled()) {
            throw new GoogleLoginException(LOCKED, "Tài khoản của bạn đã bị khoá");
        }
        if (!user.isEmailVerified()) {
            // Google has just proved the member owns this address.
            user.setEmailVerifiedAt(java.time.LocalDateTime.now());
            userRepository.save(user);
        }
        return (AppUserPrincipal) userDetailsService.loadUserByUsername(address);
    }
}
