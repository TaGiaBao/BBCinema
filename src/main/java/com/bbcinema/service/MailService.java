package com.bbcinema.service;

import com.bbcinema.config.AppProperties;
import com.bbcinema.entity.Booking;
import com.bbcinema.repository.BookingRepository;
import com.bbcinema.service.event.BookingPaidEvent;
import com.bbcinema.service.event.PasswordResetRequestedEvent;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Sends the e-ticket with its QR code after a booking is paid. Runs in the background so a slow
 * mail server never delays the payment page. When mail is turned off, it only writes a log line.
 * In a demo (app.demo-mailbox) every mail is also kept by {@link DemoMailbox} so it can be shown on screen.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

    private final ObjectProvider<JavaMailSender> mailSender;
    private final BookingRepository bookingRepository;
    private final QrCodeService qrCodeService;
    private final TemplateEngine templateEngine;
    private final AppProperties properties;
    private final DemoMailbox demoMailbox;
    private final com.bbcinema.repository.PaymentRepository paymentRepository;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onBookingPaid(BookingPaidEvent event) {
        Booking booking = bookingRepository.findDetailedByCode(event.bookingCode()).orElse(null);
        if (booking == null) {
            return;
        }
        String to = booking.getUser() != null ? booking.getUser().getEmail() : booking.getCustomerEmail();
        if (to == null || to.isBlank() || isReservedAddress(to) || isSampleBooking(booking)) {
            return;
        }
        JavaMailSender sender = mailSender.getIfAvailable();
        boolean sending = properties.mail().enabled() && sender != null;
        if (!sending && !demoMailbox.isEnabled()) {
            log.info("Mail is off. Ticket {} would be sent to {}", booking.getCode(), to);
            return;
        }
        try {
            Context context = new Context();
            context.setVariable("booking", booking);
            context.setVariable("baseUrl", properties.baseUrl());
            context.setVariable("qr", qrCodeService.modules(booking.getCode()));
            String html = templateEngine.process("mail/ticket", context);
            String subject = "[BBCinema] Vé xem phim " + booking.getShowtime().getMovie().getTitle() + " - " + booking.getCode();
            demoMailbox.keep(to, subject, html, Map.of());
            if (!sending) {
                return;
            }
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.mail().from());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            sender.send(message);
            log.info("Ticket {} sent to {}", booking.getCode(), to);
        } catch (Exception e) {
            log.error("Could not send ticket {} to {}: {}", booking.getCode(), to, e.getMessage());
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPasswordResetRequested(PasswordResetRequestedEvent event) {
        JavaMailSender sender = mailSender.getIfAvailable();
        boolean sending = properties.mail().enabled() && sender != null;
        if (!sending && !demoMailbox.isEnabled()) {
            // Demo mode without e-mail: the link is only written to the server log.
            log.info("Mail is off. Password reset link for {}: {}", event.email(), event.link());
            return;
        }
        try {
            Context context = new Context();
            context.setVariable("fullName", event.fullName());
            context.setVariable("link", event.link());
            context.setVariable("minutes", PasswordResetService.LINK_LIFETIME.toMinutes());
            String html = templateEngine.process("mail/password-reset", context);
            String subject = "[BBCinema] Đặt lại mật khẩu";
            demoMailbox.keep(event.email(), subject, html, Map.of());
            if (!sending) {
                return;
            }
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.mail().from());
            helper.setTo(event.email());
            helper.setSubject(subject);
            helper.setText(html, true);
            sender.send(message);
            log.info("Password reset mail sent to {}", event.email());
        } catch (Exception e) {
            log.error("Could not send password reset mail to {}: {}", event.email(), e.getMessage());
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEmailVerificationRequested(com.bbcinema.service.event.EmailVerificationRequestedEvent event) {
        JavaMailSender sender = mailSender.getIfAvailable();
        boolean sending = properties.mail().enabled() && sender != null;
        if (!sending && !demoMailbox.isEnabled()) {
            log.info("Mail is off. Confirmation link for {}: {}", event.email(), event.link());
            return;
        }
        try {
            Context context = new Context();
            context.setVariable("fullName", event.fullName());
            context.setVariable("link", event.link());
            context.setVariable("hours", EmailVerificationService.LINK_LIFETIME.toHours());
            String html = templateEngine.process("mail/verify-email", context);
            String subject = "[BBCinema] Xác nhận email đăng ký";
            demoMailbox.keep(event.email(), subject, html, Map.of());
            if (!sending || isReservedAddress(event.email())) {
                return;
            }
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.mail().from());
            helper.setTo(event.email());
            helper.setSubject(subject);
            helper.setText(html, true);
            sender.send(message);
            log.info("Confirmation mail sent to {}", event.email());
        } catch (Exception e) {
            log.error("Could not send confirmation mail to {}: {}", event.email(), e.getMessage());
        }
    }

    /** Bookings made by the demo data at start-up: their tickets would otherwise be mailed out for real. */
    private boolean isSampleBooking(Booking booking) {
        return paymentRepository.findByBookingIdOrderByCreatedAtDesc(booking.getId()).stream()
                .anyMatch(p -> com.bbcinema.config.DataSeeder.SEED_MARK.equals(p.getGatewayTransactionNo()));
    }

    /**
     * Addresses on domains reserved for examples (RFC 2606: example.com, .test, .invalid...) can never
     * receive mail. The demo customers use them, so their tickets are not mailed.
     */
    static boolean isReservedAddress(String email) {
        String domain = email.substring(email.lastIndexOf('@') + 1).toLowerCase(java.util.Locale.ROOT);
        return domain.matches("(.+\\.)?example\\.(com|net|org)") || domain.matches(".+\\.(test|example|invalid|localhost)");
    }
}
