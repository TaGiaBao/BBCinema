package com.bbcinema.service.event;

/** Published when someone asks for a password reset link; the mail is sent after the commit. */
public record PasswordResetRequestedEvent(String email, String fullName, String link) {
}
