package com.bbcinema.web.staff;

import com.bbcinema.TestFixtures;
import com.bbcinema.entity.Booking;
import com.bbcinema.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A booking changed by another action between loading and saving gives a flash message, not a 500 page. */
class StaffConcurrentUpdateTest {

    private static final String MESSAGE = "Vé vừa được cập nhật bởi thao tác khác, vui lòng thử lại.";

    @Test
    void checkInTurnsOptimisticLockFailureIntoFlashError() {
        BookingService bookingService = mock(BookingService.class);
        when(bookingService.checkIn("K7M2QX9P"))
                .thenThrow(new ObjectOptimisticLockingFailureException(Booking.class, 1L));
        StaffController controller = new StaffController(mock(ShowtimeService.class), bookingService,
                mock(ComboService.class), mock(BankTransferService.class), mock(ShiftService.class),
                mock(TicketPrintService.class), mock(com.bbcinema.service.MemberCodeService.class),
                mock(com.bbcinema.service.AudienceTicketService.class), TestFixtures.clock());
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        String view = controller.checkIn(" k7m2qx9p ", redirect);

        assertThat(view).isEqualTo("redirect:/staff/checkin?code=K7M2QX9P");
        assertThat(redirect.getFlashAttributes().get("error")).isEqualTo(MESSAGE);
    }

    @Test
    void refundTurnsOptimisticLockFailureIntoFlashError() {
        ShiftService shiftService = mock(ShiftService.class);
        when(shiftService.refund(anyLong(), any(), any(), any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(Booking.class, 1L));
        ShiftController controller = new ShiftController(shiftService, null);
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();
        var staff = mock(com.bbcinema.security.AppUserPrincipal.class);
        when(staff.getId()).thenReturn(7L);

        String view = controller.refund("K7M2QX9P", staff, "m@test.vn", "x", redirect);

        assertThat(view).isEqualTo("redirect:/staff/checkin?code=K7M2QX9P");
        assertThat(redirect.getFlashAttributes().get("error")).isEqualTo(MESSAGE);
    }
}
