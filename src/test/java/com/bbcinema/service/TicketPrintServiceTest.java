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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketPrintServiceTest {

    @Mock BookingRepository bookingRepository;

    TicketPrintService service;
    Booking booking;

    @BeforeEach
    void setUp() {
        service = new TicketPrintService(bookingRepository, TestFixtures.clock());
        booking = new Booking();
        booking.setCode("K7M2QX9P");
        booking.setStatus(BookingStatus.PAID);
    }

    @Test
    void eachPrintIsCountedWithItsTime() {
        when(bookingRepository.findForUpdateByCode("K7M2QX9P")).thenReturn(Optional.of(booking));

        service.recordPrint("K7M2QX9P");
        assertThat(booking.getPrintCount()).isEqualTo(1);
        assertThat(booking.getLastPrintedAt()).isEqualTo(TestFixtures.NOW);

        Booking again = service.recordPrint(" k7m2qx9p ");
        assertThat(again.getPrintCount()).isEqualTo(2);
        assertThat(again.getLastPrintedAt()).isEqualTo(TestFixtures.NOW);
    }

    @Test
    void lastPrintTimeMovesForward() {
        booking.setPrintCount(3);
        booking.setLastPrintedAt(LocalDateTime.of(2026, 11, 1, 8, 0));
        when(bookingRepository.findForUpdateByCode("K7M2QX9P")).thenReturn(Optional.of(booking));

        service.recordPrint("K7M2QX9P");

        assertThat(booking.getPrintCount()).isEqualTo(4);
        assertThat(booking.getLastPrintedAt()).isEqualTo(TestFixtures.NOW);
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = {"PENDING", "CANCELLED", "EXPIRED"})
    void onlyPaidBookingsCanBePrinted(BookingStatus status) {
        booking.setStatus(status);
        when(bookingRepository.findForUpdateByCode("K7M2QX9P")).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.recordPrint("K7M2QX9P"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("không thể in vé");
        assertThat(booking.getPrintCount()).isZero();
        assertThat(booking.getLastPrintedAt()).isNull();
    }

    @Test
    void unknownCodeIsNotFound() {
        when(bookingRepository.findForUpdateByCode("NOPE1234")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordPrint("NOPE1234")).isInstanceOf(NotFoundException.class);
    }
}
