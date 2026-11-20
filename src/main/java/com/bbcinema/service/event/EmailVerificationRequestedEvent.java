package com.bbcinema.service.event;

/** Published when a new member must confirm their e-mail address; the mail is sent after the commit. */
public record EmailVerificationRequestedEvent(String email, String fullName, String link) {
}
