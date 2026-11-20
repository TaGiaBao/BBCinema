package com.bbcinema.service;

import com.bbcinema.config.AppProperties;
import com.bbcinema.entity.User;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.repository.UserRepository;
import com.bbcinema.service.event.PasswordResetRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * "Quên mật khẩu": e-mails a one-time link that lets the owner of the address choose a new password.
 * Only a SHA-256 hash of the token is stored, the link expires after {@link #LINK_LIFETIME} and works
 * once. Whether the address exists is never revealed to the person asking.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    public static final Duration LINK_LIFETIME = Duration.ofMinutes(30);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;
    private final AppProperties properties;
    private final Clock clock;

    /** Starts a reset for this address if an active account has it; does nothing otherwise. */
    @Transactional
    public void requestReset(String email) {
        if (email == null || email.isBlank()) {
            return;
        }
        Optional<User> found = userRepository.findByEmailIgnoreCase(email.trim()).filter(User::isEnabled);
        if (found.isEmpty()) {
            log.info("Password reset asked for unknown or locked address");
            return;
        }
        User user = found.get();
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        user.setResetTokenHash(hash(token));
        user.setResetTokenExpiresAt(LocalDateTime.now(clock).plus(LINK_LIFETIME));
        String link = properties.baseUrl() + "/reset-password?token=" + token;
        events.publishEvent(new PasswordResetRequestedEvent(user.getEmail(), user.getFullName(), link));
    }

    /** True when the token belongs to an account and has not expired or been used. */
    @Transactional(readOnly = true)
    public boolean isValid(String token) {
        return findByToken(token).isPresent();
    }

    @Transactional
    public void resetPassword(String token, String newPassword, String confirmPassword) {
        User user = findByToken(token).orElseThrow(() -> new BusinessException(
                "Đường dẫn đặt lại mật khẩu không hợp lệ hoặc đã hết hạn. Vui lòng yêu cầu lại."));
        if (newPassword == null || newPassword.length() < 6 || newPassword.length() > 64) {
            throw new BusinessException("Mật khẩu từ 6 đến 64 ký tự");
        }
        if (!newPassword.equals(confirmPassword)) {
            throw new BusinessException("Mật khẩu nhập lại không khớp");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setResetTokenHash(null);
        user.setResetTokenExpiresAt(null);
        if (user.getEmailVerifiedAt() == null) {
            // Opening a link mailed to the address proves the member owns it.
            user.setEmailVerifiedAt(LocalDateTime.now(clock));
        }
        log.info("Password reset for {}", user.getEmail());
    }

    private Optional<User> findByToken(String token) {
        if (token == null || token.isBlank() || token.length() > 100) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        return userRepository.findByResetTokenHash(hash(token))
                .filter(u -> u.getResetTokenExpiresAt() != null && u.getResetTokenExpiresAt().isAfter(now))
                .filter(User::isEnabled);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
