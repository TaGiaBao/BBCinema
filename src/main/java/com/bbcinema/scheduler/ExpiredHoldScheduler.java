package com.bbcinema.scheduler;

import com.bbcinema.service.BookingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every 30 seconds, gives back the seats of bookings that were not paid in time. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpiredHoldScheduler {

    private final BookingService bookingService;

    @Scheduled(fixedDelayString = "${app.booking.expire-job-interval-ms:30000}")
    public void releaseExpiredHolds() {
        int expired = bookingService.expireOverdueBookings();
        if (expired > 0) {
            log.info("Released seats of {} expired booking(s)", expired);
        }
    }
}
