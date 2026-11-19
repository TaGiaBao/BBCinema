package com.bbcinema.service.event;

/** Published when a booking is paid. Listeners run after the transaction commits. */
public record BookingPaidEvent(String bookingCode) {
}
