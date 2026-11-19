package com.bbcinema.service;

import com.bbcinema.TestFixtures;
import com.bbcinema.dto.request.ShowtimeForm;
import com.bbcinema.entity.*;
import com.bbcinema.entity.enums.AgeRating;
import com.bbcinema.entity.enums.MovieStatus;
import com.bbcinema.entity.enums.ScreenFormat;
import com.bbcinema.entity.enums.SeatStatus;
import com.bbcinema.entity.enums.SeatType;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.repository.ShowtimeRepository;
import com.bbcinema.repository.ShowtimeSeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShowtimeServiceTest {

    @Mock ShowtimeRepository showtimeRepository;
    @Mock ShowtimeSeatRepository showtimeSeatRepository;
    @Mock MovieService movieService;
    @Mock RoomService roomService;
    @Mock PricingService pricingService;

    ShowtimeService service;
    Movie movie;
    Room room;

    @BeforeEach
    void setUp() {
        service = new ShowtimeService(showtimeRepository, showtimeSeatRepository, movieService, roomService,
                pricingService, TestFixtures.properties(), TestFixtures.clock());
        movie = TestFixtures.movie(1, 120, AgeRating.P);
        room = TestFixtures.room(1);
    }

    @Test
    void parsesManyTimeFormats() {
        assertThat(ShowtimeService.parseTimes("19:30, 9:15;21h 13h45"))
                .containsExactly(LocalTime.of(9, 15), LocalTime.of(13, 45), LocalTime.of(19, 30), LocalTime.of(21, 0));
    }

    @Test
    void rejectsBadTime() {
        assertThatThrownBy(() -> ShowtimeService.parseTimes("25:00")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ShowtimeService.parseTimes(" , ")).isInstanceOf(BusinessException.class);
    }

    @Test
    void expiredHoldCountsAsFree() {
        Showtime st = TestFixtures.showtime(1, movie, room, TestFixtures.NOW.plusDays(1));
        ShowtimeSeat ss = TestFixtures.seat(1, st, "A", 1, 60_000);
        ss.setStatus(SeatStatus.HELD);
        ss.setHoldExpiresAt(TestFixtures.NOW.minusSeconds(1));
        assertThat(ShowtimeService.effectiveStatus(ss, TestFixtures.NOW)).isEqualTo(SeatStatus.AVAILABLE);
        ss.setHoldExpiresAt(TestFixtures.NOW.plusMinutes(1));
        assertThat(ShowtimeService.effectiveStatus(ss, TestFixtures.NOW)).isEqualTo(SeatStatus.HELD);
    }

    @Test
    void createComputesEndTimeAndPricesEverySeat() {
        Seat a1 = new Seat("A", 1, SeatType.STANDARD);
        Seat a2 = new Seat("A", 2, SeatType.VIP);
        Seat gap = new Seat("A", 3, SeatType.STANDARD);
        gap.setActive(false);
        when(roomService.getSeats(1L)).thenReturn(List.of(a1, a2, gap));
        when(showtimeRepository.findOverlapping(anyLong(), any(), any(), isNull())).thenReturn(List.of());
        when(showtimeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pricingService.priceFor(eq(SeatType.STANDARD), any(), any())).thenReturn(BigDecimal.valueOf(60_000));
        when(pricingService.priceFor(eq(SeatType.VIP), any(), any())).thenReturn(BigDecimal.valueOf(70_000));

        LocalDateTime start = TestFixtures.NOW.plusDays(1).withHour(19).withMinute(0);
        Showtime created = service.create(movie, room, start, ScreenFormat.TWO_D, false);

        assertThat(created.getEndTime()).isEqualTo(start.plusMinutes(135));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ShowtimeSeat>> captor = ArgumentCaptor.forClass(List.class);
        verify(showtimeSeatRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2)
                .extracting(ShowtimeSeat::getPrice).containsExactly(BigDecimal.valueOf(60_000), BigDecimal.valueOf(70_000));
    }

    @Test
    void createRejectsOverlap() {
        Showtime other = TestFixtures.showtime(9, TestFixtures.movie(2, 100, AgeRating.P), room, TestFixtures.NOW.plusDays(1));
        when(showtimeRepository.findOverlapping(anyLong(), any(), any(), isNull())).thenReturn(List.of(other));

        assertThatThrownBy(() -> service.create(movie, room, TestFixtures.NOW.plusDays(1).plusMinutes(30), ScreenFormat.TWO_D, false))
                .isInstanceOf(BusinessException.class).hasMessageContaining("trùng giờ");
        verify(showtimeRepository, never()).save(any());
    }

    @Test
    void createRejectsInactiveRoom() {
        room.setActive(false);
        assertThatThrownBy(() -> service.create(movie, room, TestFixtures.NOW.plusDays(1), ScreenFormat.TWO_D, false))
                .hasMessageContaining("tạm ngưng");
    }

    @Test
    void batchCreationRejectsPastTimesAndEndedMovies() {
        when(movieService.getById(1L)).thenReturn(movie);
        when(roomService.getById(1L)).thenReturn(room);
        ShowtimeForm form = new ShowtimeForm();
        form.setMovieId(1L);
        form.setRoomId(1L);
        form.setFromDate(TestFixtures.NOW.toLocalDate());
        form.setTimes("08:00");
        assertThatThrownBy(() -> service.createShowtimes(form)).hasMessageContaining("quá khứ");

        movie.setStatus(MovieStatus.ENDED);
        assertThatThrownBy(() -> service.createShowtimes(form)).hasMessageContaining("ngừng chiếu");
    }

    @Test
    void batchCreationMakesOneShowtimePerDayAndTime() {
        when(movieService.getById(1L)).thenReturn(movie);
        when(roomService.getById(1L)).thenReturn(room);
        when(showtimeRepository.findOverlapping(anyLong(), any(), any(), isNull())).thenReturn(List.of());
        when(showtimeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(roomService.getSeats(1L)).thenReturn(List.of());
        ShowtimeForm form = new ShowtimeForm();
        form.setMovieId(1L);
        form.setRoomId(1L);
        form.setFromDate(TestFixtures.NOW.toLocalDate().plusDays(1));
        form.setToDate(TestFixtures.NOW.toLocalDate().plusDays(3));
        form.setTimes("09:00, 14:00");

        assertThat(service.createShowtimes(form)).isEqualTo(6);
    }

    @Test
    void cancelRefusesShowtimesWithTickets() {
        Showtime st = TestFixtures.showtime(1, movie, room, TestFixtures.NOW.plusDays(1));
        when(showtimeRepository.findDetailedById(1L)).thenReturn(Optional.of(st));
        when(showtimeSeatRepository.countByShowtimeIdAndStatus(1L, SeatStatus.BOOKED)).thenReturn(3L);

        assertThatThrownBy(() -> service.cancel(1L)).hasMessageContaining("3 ghế");
        assertThat(st.isCancelled()).isFalse();
    }

    @Test
    void upcomingDatesStartToday() {
        assertThat(service.upcomingDates()).hasSize(7).first().isEqualTo(TestFixtures.NOW.toLocalDate());
    }
}
