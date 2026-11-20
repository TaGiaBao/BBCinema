package com.bbcinema.service;

import com.bbcinema.config.AppProperties;
import com.bbcinema.entity.User;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.repository.UserRepository;
import com.bbcinema.service.event.EmailVerificationRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;

/**
 * Confirms that a new member owns the e-mail address they signed up with, since tickets are sent there.
 * A one-time link is mailed; only a SHA-256 hash of its token is stored and it expires after
 * {@link #LINK_LIFETIME}. Asking for a new link never reveals whether an address has an account.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    public static final Duration LINK_LIFETIME = Duration.ofHours(24);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final ApplicationEventPublisher events;
    private final AppProperties properties;
    private final Clock clock;

    /** Mails a fresh confirmation link to a member who has not confirmed yet. */
    @Transactional
    public void sendLink(Long userId) {
        userRepository.findById(userId).filter(u -> !u.isEmailVerified()).ifPresent(this::issueLink);
    }

    /** "Gửi lại email xác nhận": same answer whatever the address, so accounts cannot be probed. */
    @Transactional
    public void resend(String email) {
        if (email == null || email.isBlank()) {
            return;
        }
        userRepository.findByEmailIgnoreCase(email.trim())
                .filter(u -> !u.isEmailVerified() && u.isEnabled())
                .ifPresentOrElse(this::issueLink, () -> log.info("Confirmation link asked for an unknown or confirmed address"));
    }

    /** Opens the link: the address is confirmed and the link stops working. Returns the member's address. */
    @Transactional
    public String verify(String token) {
        User user = findByToken(token).orElseThrow(() -> new BusinessException(
                "Đường dẫn xác nhận không hợp lệ hoặc đã hết hạn. Hãy đăng nhập để được gửi lại email xác nhận."));
        markVerified(user);
        log.info("E-mail confirmed for {}", user.getEmail());
        return user.getEmail();
    }

    /** Also used when the address is proved another way (Google sign-in, a password reset link). */
    public void markVerified(User user) {
        if (!user.isEmailVerified()) {
            user.setEmailVerifiedAt(LocalDateTime.now(clock));
        }
        user.setVerifyTokenHash(null);
        user.setVerifyTokenExpiresAt(null);
    }

    private void issueLink(User user) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        user.setVerifyTokenHash(PasswordResetService.hash(token));
        user.setVerifyTokenExpiresAt(LocalDateTime.now(clock).plus(LINK_LIFETIME));
        String link = properties.baseUrl() + "/verify-email?token=" + token;
        events.publishEvent(new EmailVerificationRequestedEvent(user.getEmail(), user.getFullName(), link));
    }

    private Optional<User> findByToken(String token) {
        if (token == null || token.isBlank() || token.length() > 100) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        return userRepository.findByVerifyTokenHash(PasswordResetService.hash(token))
                .filter(u -> u.getVerifyTokenExpiresAt() != null && u.getVerifyTokenExpiresAt().isAfter(now));
    }
}
