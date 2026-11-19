package com.bbcinema.service;

import com.bbcinema.TestFixtures;
import com.bbcinema.dto.request.RoomForm;
import com.bbcinema.entity.Room;
import com.bbcinema.entity.Seat;
import com.bbcinema.entity.enums.SeatType;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.repository.RoomRepository;
import com.bbcinema.repository.SeatRepository;
import com.bbcinema.repository.ShowtimeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomServiceTest {

    @Mock RoomRepository roomRepository;
    @Mock SeatRepository seatRepository;
    @Mock ShowtimeRepository showtimeRepository;
    @Mock CinemaService cinemaService;
    @InjectMocks RoomService service;

    private RoomForm form(int rows, int perRow, String vip, String couple) {
        RoomForm form = new RoomForm();
        form.setCinemaId(1L);
        form.setName("Phòng 9");
        form.setRowCount(rows);
        form.setSeatsPerRow(perRow);
        form.setVipRows(vip);
        form.setCoupleRows(couple);
        return form;
    }

    @Test
    void generatesSeatsWithTypesAndHalfSizeCoupleRows() {
        when(cinemaService.getById(1L)).thenReturn(TestFixtures.room(1).getCinema());
        when(roomRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Room room = service.create(form(4, 10, "c", "D, Z"));

        assertThat(room.getSeats()).hasSize(10 + 10 + 10 + 5);
        assertThat(room.getSeats()).filteredOn(s -> s.getRowLabel().equals("C")).allMatch(s -> s.getType() == SeatType.VIP);
        assertThat(room.getSeats()).filteredOn(s -> s.getRowLabel().equals("D")).allMatch(s -> s.getType() == SeatType.COUPLE);
        assertThat(room.getSeats()).filteredOn(s -> s.getRowLabel().equals("A")).allMatch(s -> s.getType() == SeatType.STANDARD);
        assertThat(room.getSeats()).allMatch(s -> s.getRoom() == room);
    }

    @Test
    void rowCannotBeVipAndCouple() {
        when(cinemaService.getById(1L)).thenReturn(TestFixtures.room(1).getCinema());
        assertThatThrownBy(() -> service.create(form(4, 10, "B", "B"))).isInstanceOf(BusinessException.class);
    }

    @Test
    void rowLabelsAreLetters() {
        assertThat(RoomService.rowLabels(3)).containsExactly("A", "B", "C");
        assertThat(RoomService.parseRows(" e, f ,,g")).containsExactlyInAnyOrder("E", "F", "G");
        assertThat(RoomService.parseRows(null)).isEmpty();
    }

    @Test
    void updateSeatChecksRoom() {
        Room room = TestFixtures.room(1);
        Seat seat = new Seat("A", 1, SeatType.STANDARD);
        seat.setRoom(room);
        when(seatRepository.findById(5L)).thenReturn(Optional.of(seat));

        Seat updated = service.updateSeat(1L, 5L, SeatType.VIP, false);
        assertThat(updated.getType()).isEqualTo(SeatType.VIP);
        assertThat(updated.isActive()).isFalse();

        assertThatThrownBy(() -> service.updateSeat(2L, 5L, SeatType.VIP, true)).hasMessageContaining("Không tìm thấy ghế");
    }

    @Test
    void defaultRoomFormGivesStandardLayout() {
        when(cinemaService.getById(1L)).thenReturn(TestFixtures.room(1).getCinema());
        when(roomRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        RoomForm form = new RoomForm();
        form.setCinemaId(1L);
        form.setName("Phòng mới");

        Room room = service.create(form);

        assertThat(room.getRowCount()).isEqualTo(10);
        for (String row : new String[]{"A", "B", "C", "D", "E"}) {
            assertThat(room.getSeats()).filteredOn(s -> s.getRowLabel().equals(row)).allMatch(s -> s.getType() == SeatType.STANDARD);
        }
        for (String row : new String[]{"F", "G", "H", "I"}) {
            assertThat(room.getSeats()).filteredOn(s -> s.getRowLabel().equals(row)).isNotEmpty()
                    .allMatch(s -> s.getType() == SeatType.VIP);
        }
        assertThat(room.getSeats()).filteredOn(s -> s.getType() == SeatType.COUPLE)
                .isNotEmpty().allMatch(s -> s.getRowLabel().equals("J"));
    }

    @Test
    void coupleSeatsOnlyInLastRow() {
        when(cinemaService.getById(1L)).thenReturn(TestFixtures.room(1).getCinema());
        assertThatThrownBy(() -> service.create(form(10, 12, "F,G", "H")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("hàng cuối");
    }

    @Test
    void seatEditorCannotMakeCoupleSeatOutsideLastRow() {
        Room room = TestFixtures.room(1); // rows A-B
        Seat front = new Seat("A", 1, SeatType.STANDARD);
        front.setRoom(room);
        Seat back = new Seat("B", 1, SeatType.STANDARD);
        back.setRoom(room);
        when(seatRepository.findById(5L)).thenReturn(Optional.of(front));
        when(seatRepository.findById(6L)).thenReturn(Optional.of(back));

        assertThatThrownBy(() -> service.updateSeat(1L, 5L, SeatType.COUPLE, true)).hasMessageContaining("hàng cuối");
        assertThat(service.updateSeat(1L, 6L, SeatType.COUPLE, true).getType()).isEqualTo(SeatType.COUPLE);
    }

    @Test
    void roomNeedsAtLeastNineRows() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            RoomForm form = form(8, 12, "F,G,H", "");
            assertThat(validator.validate(form)).anyMatch(v -> v.getPropertyPath().toString().equals("rowCount"));
            form.setRowCount(9);
            assertThat(validator.validate(form)).isEmpty();
        }
    }
}
