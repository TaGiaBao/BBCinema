package com.bbcinema.service;

import com.bbcinema.entity.Booking;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.exception.NotFoundException;
import com.bbcinema.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;

/** Print log of paper tickets: counts each time staff print a ticket at the cinema. */
@Service
@RequiredArgsConstructor
public class TicketPrintService {

    private final BookingRepository bookingRepository;
    private final Clock clock;

    /**
     * Records one print of the paper ticket of a paid booking. Unpaid, cancelled (refunded) and expired
     * bookings have no valid ticket, so they cannot be printed.
     */
    @Transactional
    public Booking recordPrint(String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        Booking booking = bookingRepository.findForUpdateByCode(normalized)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy đơn đặt vé " + normalized));
        if (!booking.isPaid()) {
            throw new BusinessException("Đơn " + booking.getCode() + " " + booking.getStatus().getLabel().toLowerCase(Locale.ROOT)
                    + ", không thể in vé");
        }
        booking.setPrintCount(booking.getPrintCount() + 1);
        booking.setLastPrintedAt(LocalDateTime.now(clock));
        return booking;
    }
}
