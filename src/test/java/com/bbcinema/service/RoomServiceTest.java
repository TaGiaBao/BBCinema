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
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
    @Mock com.bbcinema.repository.ShowtimeSeatRepository showtimeSeatRepository;
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
    void roomNeedsAtLeastNineRows() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            RoomForm form = form(8, 12, "F,G,H", "");
            assertThat(validator.validate(form)).anyMatch(v -> v.getPropertyPath().toString().equals("rowCount"));
            form.setRowCount(9);
            assertThat(validator.validate(form)).isEmpty();
        }
    }

    @Test
    void newRoomSeatsGetGridPositionsAndCoupleRowIsCentred() {
        when(cinemaService.getById(1L)).thenReturn(TestFixtures.room(1).getCinema());
        when(roomRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Room room = service.create(form(3, 5, "B", "C"));

        Seat a1 = room.getSeats().stream().filter(s -> s.getLabel().equals("A1")).findFirst().orElseThrow();
        Seat a5 = room.getSeats().stream().filter(s -> s.getLabel().equals("A5")).findFirst().orElseThrow();
        assertThat(a1.getGridX()).isZero();
        assertThat(a1.getGridY()).isZero();
        assertThat(a5.getGridX()).isEqualTo(4);
        // Row C: 2 couple seats (4 columns) centred under 5 columns -> start at column 0.
        assertThat(room.getSeats()).filteredOn(s -> s.getRowLabel().equals("C"))
                .extracting(Seat::getGridX).containsExactly(0, 2);
        assertThat(room.getSeats()).filteredOn(s -> s.getRowLabel().equals("C")).allMatch(s -> s.getGridY() == 2);
    }

    @Test
    void coupleSeatsCanBeInAnyRowNow() {
        when(cinemaService.getById(1L)).thenReturn(TestFixtures.room(1).getCinema());
        when(roomRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Room room = service.create(form(10, 12, "F,G", "C"));
        assertThat(room.getSeats()).filteredOn(s -> s.getRowLabel().equals("C")).allMatch(s -> s.getType() == SeatType.COUPLE);
    }

    private static RoomService.LayoutSeat at(Long id, String row, int number, SeatType type, int x, int y) {
        return new RoomService.LayoutSeat(id, row, number, type, true, x, y);
    }

    @Test
    void layoutAcceptsSeatsAnywhereWithoutOverlap() {
        RoomService.validateLayout(List.of(
                at(1L, "A", 1, SeatType.STANDARD, 0, 0),
                at(2L, "A", 2, SeatType.STANDARD, 3, 0),      // an aisle between A1 and A2
                at(null, "K", 1, SeatType.COUPLE, 5, 7),      // a new couple seat far back
                at(null, "k", 2, SeatType.VIP, 7, 7)), Set.of(1L, 2L));
    }

    @Test
    void layoutRefusesOverlapsIncludingTheSecondCellOfACoupleSeat() {
        assertThatThrownBy(() -> RoomService.validateLayout(List.of(
                at(null, "J", 1, SeatType.COUPLE, 0, 0),
                at(null, "J", 2, SeatType.STANDARD, 1, 0)), Set.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("J2 chồng lên ghế J1");
    }

    @Test
    void layoutRefusesDuplicateNamesBadNamesAndSeatsOffTheGrid() {
        assertThatThrownBy(() -> RoomService.validateLayout(List.of(
                at(null, "A", 1, SeatType.STANDARD, 0, 0),
                at(null, "a", 1, SeatType.STANDARD, 1, 0)), Set.of()))
                .hasMessageContaining("hai ghế cùng tên A1");
        assertThatThrownBy(() -> RoomService.validateLayout(List.of(
                at(null, "A1", 1, SeatType.STANDARD, 0, 0)), Set.of()))
                .hasMessageContaining("không hợp lệ");
        assertThatThrownBy(() -> RoomService.validateLayout(List.of(
                at(null, "A", 1, SeatType.COUPLE, SeatLayout.MAX_COLUMNS - 1, 0)), Set.of()))
                .hasMessageContaining("ngoài sơ đồ");
        assertThatThrownBy(() -> RoomService.validateLayout(List.of(), Set.of()))
                .hasMessageContaining("ít nhất một ghế");
    }

    @Test
    void layoutRefusesSeatsOfAnotherRoom() {
        assertThatThrownBy(() -> RoomService.validateLayout(List.of(
                at(99L, "A", 1, SeatType.STANDARD, 0, 0)), Set.of(1L)))
                .hasMessageContaining("không hợp lệ");
    }
}
