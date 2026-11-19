package com.bbcinema.exception;

/** The current user tried to open or change a booking that belongs to someone else. */
public class AccessDeniedBookingException extends RuntimeException {

    public AccessDeniedBookingException() {
        super("Bạn không có quyền xem đơn đặt vé này.");
    }
}
