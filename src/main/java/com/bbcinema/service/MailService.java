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
import org.springframework.core.io.ByteArrayResource;
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

/**
 * Sends the e-ticket with its QR code after a booking is paid. Runs in the background so a slow
 * mail server never delays the payment page. When mail is turned off, it only writes a log line.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

    private final ObjectProvider<JavaMailSender> mailSender;
    private final BookingRepository bookingRepository;
    private final QrCodeService qrCodeService;
    private final TicketWatermark ticketWatermark;
    private final TemplateEngine templateEngine;
    private final AppProperties properties;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onBookingPaid(BookingPaidEvent event) {
        Booking booking = bookingRepository.findDetailedByCode(event.bookingCode()).orElse(null);
        if (booking == null) {
            return;
        }
        String to = booking.getUser() != null ? booking.getUser().getEmail() : booking.getCustomerEmail();
        if (to == null || to.isBlank()) {
            return;
        }
        JavaMailSender sender = mailSender.getIfAvailable();
        if (!properties.mail().enabled() || sender == null) {
            log.info("Mail is off. Ticket {} would be sent to {}", booking.getCode(), to);
            return;
        }
        try {
            Context context = new Context();
            context.setVariable("booking", booking);
            context.setVariable("baseUrl", properties.baseUrl());
            String html = templateEngine.process("mail/ticket", context);

            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.mail().from());
            helper.setTo(to);
            helper.setSubject("[BBCinema] Vé xem phim " + booking.getShowtime().getMovie().getTitle() + " - " + booking.getCode());
            helper.setText(html, true);
            helper.addInline("qr", new ByteArrayResource(qrCodeService.png(booking.getCode(), 240)), "image/png");
            helper.addInline("watermark", new ByteArrayResource(ticketWatermark.png()), "image/png");
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
        if (!properties.mail().enabled() || sender == null) {
            // Demo mode without e-mail: the link is only written to the server log.
            log.info("Mail is off. Password reset link for {}: {}", event.email(), event.link());
            return;
        }
        try {
            Context context = new Context();
            context.setVariable("fullName", event.fullName());
            context.setVariable("link", event.link());
            context.setVariable("minutes", PasswordResetService.LINK_LIFETIME.toMinutes());
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.mail().from());
            helper.setTo(event.email());
            helper.setSubject("[BBCinema] Đặt lại mật khẩu");
            helper.setText(templateEngine.process("mail/password-reset", context), true);
            sender.send(message);
            log.info("Password reset mail sent to {}", event.email());
        } catch (Exception e) {
            log.error("Could not send password reset mail to {}: {}", event.email(), e.getMessage());
        }
    }
}
