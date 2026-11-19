package com.bbcinema.exception;

/** A rule of the business was broken; the message is shown to the user as is. */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }
}
