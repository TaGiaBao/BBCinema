package com.bbcinema.service;

import com.bbcinema.TestFixtures;
import com.bbcinema.entity.Booking;
import com.bbcinema.entity.enums.BookingStatus;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.exception.NotFoundException;
import com.bbcinema.repository.BookingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TicketPrintServiceTest {

    @Mock BookingRepository bookingRepository;

    TicketPrintService service;

    @BeforeEach
    void setUp() {
        service = new TicketPrintService(bookingRepository, TestFixtures.clock());
    }

    private static BookingRepository.PrintLog log(int count, LocalDateTime at) {
        return new BookingRepository.PrintLog() {
            public int getPrintCount() { return count; }
            public LocalDateTime getLastPrintedAt() { return at; }
        };
    }

    @Test
    void printIsCountedAtomicallyForPaidBookingsAndReadBack() {
        when(bookingRepository.incrementPrintCount("K7M2QX9P", BookingStatus.PAID, TestFixtures.NOW)).thenReturn(1);
        when(bookingRepository.findPrintLogByCode("K7M2QX9P")).thenReturn(Optional.of(log(4, TestFixtures.NOW)));

        TicketPrintService.PrintLog result = service.recordPrint(" k7m2qx9p ");

        assertThat(result.printCount()).isEqualTo(4);
        assertThat(result.lastPrintedAt()).isEqualTo(TestFixtures.NOW);
        verify(bookingRepository, never()).findDetailedByCode(any());
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = {"PENDING", "CANCELLED", "EXPIRED"})
    void onlyPaidBookingsCanBePrinted(BookingStatus status) {
        Booking booking = new Booking();
        booking.setCode("K7M2QX9P");
        booking.setStatus(status);
        when(bookingRepository.incrementPrintCount("K7M2QX9P", BookingStatus.PAID, TestFixtures.NOW)).thenReturn(0);
        when(bookingRepository.findDetailedByCode("K7M2QX9P")).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.recordPrint("K7M2QX9P"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("không thể in vé");
        verify(bookingRepository, never()).findPrintLogByCode(any());
    }

    @Test
    void unknownCodeIsNotFound() {
        when(bookingRepository.incrementPrintCount("NOPE1234", BookingStatus.PAID, TestFixtures.NOW)).thenReturn(0);
        when(bookingRepository.findDetailedByCode("NOPE1234")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordPrint("NOPE1234")).isInstanceOf(NotFoundException.class);
    }
}
