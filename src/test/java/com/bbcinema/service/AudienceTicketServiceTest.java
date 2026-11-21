package com.bbcinema.service;

import com.bbcinema.TestFixtures;
import com.bbcinema.entity.AudienceDiscount;
import com.bbcinema.entity.Booking;
import com.bbcinema.entity.Showtime;
import com.bbcinema.entity.ShowtimeSeat;
import com.bbcinema.entity.enums.AgeRating;
import com.bbcinema.entity.enums.AudienceType;
import com.bbcinema.entity.enums.DiscountType;
import com.bbcinema.entity.enums.SeatType;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.repository.AudienceDiscountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AudienceTicketServiceTest {

    AudienceDiscountRepository repository = mock(AudienceDiscountRepository.class);
    HolidayService holidayService = mock(HolidayService.class);
    AudienceTicketService service;
    AudienceDiscount student;
    AudienceDiscount child;
    AudienceDiscount senior;

    @BeforeEach
    void setUp() {
        service = new AudienceTicketService(repository, holidayService, TestFixtures.clock());
        student = new AudienceDiscount(AudienceType.STUDENT, DiscountType.PERCENT, 20);
        child = new AudienceDiscount(AudienceType.CHILD, DiscountType.AMOUNT, 25_000);
        senior = new AudienceDiscount(AudienceType.SENIOR, DiscountType.PERCENT, 30);
        when(repository.findAll()).thenReturn(List.of(student, child, senior));
        when(holidayService.holidayDatesIn(anyList())).thenReturn(Set.of());
    }

    private static Showtime showtime(AgeRating rating) {
        return TestFixtures.showtime(10, TestFixtures.movie(1, 100, rating), TestFixtures.room(1),
                TestFixtures.NOW.plusDays(1).withHour(19));
    }

    private static Booking booking(Showtime showtime, ShowtimeSeat... seats) {
        Booking booking = new Booking();
        booking.setShowtime(showtime);
        for (ShowtimeSeat ss : seats) {
            ss.setBooking(booking);
            booking.getSeats().add(ss);
        }
        return booking;
    }

    @Test
    void percentIsRoundedDownToAThousandAndAmountsNeverExceedThePrice() {
        assertThat(AudienceTicketService.discountOn(DiscountType.PERCENT, BigDecimal.valueOf(20), BigDecimal.valueOf(75_000)))
                .isEqualByComparingTo("15000");
        assertThat(AudienceTicketService.discountOn(DiscountType.PERCENT, BigDecimal.valueOf(30), BigDecimal.valueOf(75_000)))
                .isEqualByComparingTo("22000"); // 22,500 -> 22,000
        assertThat(AudienceTicketService.discountOn(DiscountType.AMOUNT, BigDecimal.valueOf(25_000), BigDecimal.valueOf(60_000)))
                .isEqualByComparingTo("25000");
        assertThat(AudienceTicketService.discountOn(DiscountType.AMOUNT, BigDecimal.valueOf(90_000), BigDecimal.valueOf(60_000)))
                .isEqualByComparingTo("60000");
    }

    @Test
    void appliesEachSeatsTypeAndAddsUpTheDiscount() {
        Showtime st = showtime(AgeRating.P);
        ShowtimeSeat a1 = TestFixtures.seat(1, st, "A", 1, 60_000);
        ShowtimeSeat a2 = TestFixtures.seat(2, st, "A", 2, 60_000);
        ShowtimeSeat a3 = TestFixtures.seat(3, st, "A", 3, 60_000);
        Booking booking = booking(st, a1, a2, a3);

        service.apply(booking, Map.of(1L, AudienceType.STUDENT, 2L, AudienceType.CHILD, 3L, AudienceType.ADULT));

        assertThat(a1.getAudienceType()).isEqualTo(AudienceType.STUDENT);
        assertThat(a1.getAudienceDiscount()).isEqualByComparingTo("12000");
        assertThat(a1.getChargedPrice()).isEqualByComparingTo("48000");
        assertThat(a2.getAudienceDiscount()).isEqualByComparingTo("25000");
        assertThat(a3.getAudienceType()).isNull();
        assertThat(a3.getTicketType()).isEqualTo(AudienceType.ADULT);
        assertThat(booking.getAudienceDiscount()).isEqualByComparingTo("37000");
        assertThat(booking.hasAudienceTickets()).isTrue();
    }

    @Test
    void childTicketsOnlyForFilmsRatedPOrK() {
        Showtime st = showtime(AgeRating.T13);
        Booking booking = booking(st, TestFixtures.seat(1, st, "A", 1, 60_000));

        assertThatThrownBy(() -> service.apply(booking, Map.of(1L, AudienceType.CHILD)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("chỉ cho phim p, k");
        assertThat(service.optionsFor(showtime(AgeRating.K)).stream()
                .filter(o -> o.type() == AudienceType.CHILD).findFirst().orElseThrow().available()).isTrue();
    }

    @Test
    void coupleSeatsAreNeverDiscounted() {
        Showtime st = showtime(AgeRating.P);
        ShowtimeSeat couple = TestFixtures.seat(1, st, "J", 1, 180_000);
        couple.getSeat().setType(SeatType.COUPLE);
        Booking booking = booking(st, couple);

        assertThatThrownBy(() -> service.apply(booking, Map.of(1L, AudienceType.SENIOR)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Ghế đôi");
    }

    @Test
    void typesSwitchedOffOrNotForHolidaysCannotBeSold() {
        student.setEnabled(false);
        Showtime st = showtime(AgeRating.P);
        Booking booking = booking(st, TestFixtures.seat(1, st, "A", 1, 60_000));
        assertThatThrownBy(() -> service.apply(booking, Map.of(1L, AudienceType.STUDENT)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("đang tắt");

        LocalDate day = st.getStartTime().toLocalDate();
        when(holidayService.holidayDatesIn(anyList())).thenReturn(Set.of(day));
        assertThatThrownBy(() -> service.apply(booking, Map.of(1L, AudienceType.SENIOR)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("ngày lễ");
        senior.setOnHolidays(true);
        service.apply(booking, Map.of(1L, AudienceType.SENIOR));
        assertThat(booking.getAudienceDiscount()).isEqualByComparingTo("18000");
    }

    @Test
    void settingsAreChecked() {
        assertThatThrownBy(() -> AudienceTicketService.validate(AudienceType.STUDENT,
                new AudienceTicketService.Setting(true, DiscountType.PERCENT, BigDecimal.valueOf(100), false)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> AudienceTicketService.validate(AudienceType.STUDENT,
                new AudienceTicketService.Setting(true, DiscountType.PERCENT, new BigDecimal("12.5"), false)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> AudienceTicketService.validate(AudienceType.CHILD,
                new AudienceTicketService.Setting(true, DiscountType.AMOUNT, BigDecimal.valueOf(500), false)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> AudienceTicketService.validate(AudienceType.ADULT,
                new AudienceTicketService.Setting(true, DiscountType.PERCENT, BigDecimal.TEN, false)))
                .isInstanceOf(BusinessException.class);
        AudienceTicketService.validate(AudienceType.SENIOR,
                new AudienceTicketService.Setting(false, DiscountType.AMOUNT, BigDecimal.valueOf(20_000), true));
    }

    @Test
    void missingRowsFallBackToDefaults() {
        when(repository.findAll()).thenReturn(List.of());
        assertThat(service.settings()).extracting(AudienceDiscount::getAudienceType)
                .containsExactly(AudienceType.STUDENT, AudienceType.CHILD, AudienceType.SENIOR);
        assertThat(service.settings().get(0).getDiscountValue()).isEqualByComparingTo("20");
    }
}
