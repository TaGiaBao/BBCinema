package com.bbcinema.service;

import com.bbcinema.entity.Booking;
import com.bbcinema.entity.enums.BookingStatus;
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

    /** The print log of a booking after a print. */
    public record PrintLog(int printCount, LocalDateTime lastPrintedAt) {
    }

    /**
     * Records one print of the paper ticket of a paid booking. Unpaid, cancelled (refunded) and expired
     * bookings have no valid ticket, so they cannot be printed. The count goes up in one atomic UPDATE that does
     * not touch the booking's version, so it never makes a concurrent check-in or refund fail.
     */
    @Transactional
    public PrintLog recordPrint(String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        int updated = bookingRepository.incrementPrintCount(normalized, BookingStatus.PAID, LocalDateTime.now(clock));
        if (updated == 0) {
            Booking booking = bookingRepository.findDetailedByCode(normalized)
                    .orElseThrow(() -> new NotFoundException("Không tìm thấy đơn đặt vé " + normalized));
            throw new BusinessException("Đơn " + booking.getCode() + " " + booking.getStatus().getLabel().toLowerCase(Locale.ROOT)
                    + ", không thể in vé");
        }
        BookingRepository.PrintLog log = bookingRepository.findPrintLogByCode(normalized).orElseThrow();
        return new PrintLog(log.getPrintCount(), log.getLastPrintedAt());
    }
}
