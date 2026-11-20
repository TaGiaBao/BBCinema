package com.bbcinema.exception;

import com.bbcinema.security.LoginAttemptService;

/** Login refused because the email or the client address made too many failed attempts (HTTP 429 on the API). */
public class LoginBlockedException extends RuntimeException {

    public LoginBlockedException() {
        super(LoginAttemptService.BLOCKED_MESSAGE);
    }
}
