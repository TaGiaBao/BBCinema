package com.bbcinema.service;

import com.bbcinema.dto.request.RoomForm;
import com.bbcinema.entity.Cinema;
import com.bbcinema.entity.Room;
import com.bbcinema.entity.Seat;
import com.bbcinema.entity.enums.SeatType;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.exception.NotFoundException;
import com.bbcinema.repository.RoomRepository;
import com.bbcinema.repository.SeatRepository;
import com.bbcinema.repository.ShowtimeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RoomService {

    private final RoomRepository roomRepository;
    private final SeatRepository seatRepository;
    private final ShowtimeRepository showtimeRepository;
    private final CinemaService cinemaService;

    public List<Room> findAll() {
        return roomRepository.findAllWithCinema();
    }

    public List<Room> findActiveByCinema(Long cinemaId) {
        return roomRepository.findByCinemaIdAndActiveTrueOrderByNameAsc(cinemaId);
    }

    public Room getById(Long id) {
        return roomRepository.findById(id).orElseThrow(() -> new NotFoundException("Không tìm thấy phòng chiếu"));
    }

    public List<Seat> getSeats(Long roomId) {
        return seatRepository.findByRoomIdOrderByRowLabelAscNumberAsc(roomId);
    }

    /**
     * Creates a room and generates its seats: rows A, B, C... with {@code seatsPerRow} seats each.
     * Rows listed in vipRows become VIP and rows in coupleRows become couple seats. Couple seats
     * are only allowed in the last row, at the back of the room.
     */
    @Transactional
    public Room create(RoomForm form) {
        Cinema cinema = cinemaService.getById(form.getCinemaId());
        Set<String> vipRows = parseRows(form.getVipRows());
        Set<String> coupleRows = parseRows(form.getCoupleRows());
        vipRows.retainAll(rowLabels(form.getRowCount()));
        coupleRows.retainAll(rowLabels(form.getRowCount()));
        if (vipRows.stream().anyMatch(coupleRows::contains)) {
            throw new BusinessException("Một hàng không thể vừa là VIP vừa là ghế đôi");
        }
        String lastRow = lastRowLabel(form.getRowCount());
        if (coupleRows.stream().anyMatch(row -> !row.equals(lastRow))) {
            throw new BusinessException("Ghế đôi chỉ đặt ở hàng cuối cùng của phòng (hàng " + lastRow + ")");
        }

        Room room = new Room();
        room.setCinema(cinema);
        room.setName(form.getName().trim());
        room.setRowCount(form.getRowCount());
        room.setSeatsPerRow(form.getSeatsPerRow());
        for (String row : rowLabels(form.getRowCount())) {
            SeatType type = vipRows.contains(row) ? SeatType.VIP
                    : coupleRows.contains(row) ? SeatType.COUPLE : SeatType.STANDARD;
            // A couple seat is as wide as two normal seats, so a couple row holds half as many.
            int seatsInRow = type == SeatType.COUPLE ? Math.max(1, form.getSeatsPerRow() / 2) : form.getSeatsPerRow();
            for (int number = 1; number <= seatsInRow; number++) {
                room.addSeat(new Seat(row, number, type));
            }
        }
        return roomRepository.save(room);
    }

    @Transactional
    public void rename(Long roomId, String name, boolean active) {
        if (name == null || name.isBlank()) {
            throw new BusinessException("Vui lòng nhập tên phòng");
        }
        Room room = getById(roomId);
        room.setName(name.trim());
        room.setActive(active);
    }

    /**
     * Changes the type of a seat or turns it into a gap. Showtimes that already exist keep the seat
     * map they were created with; the change applies to showtimes created afterwards.
     */
    @Transactional
    public Seat updateSeat(Long roomId, Long seatId, SeatType type, boolean active) {
        Seat seat = seatRepository.findById(seatId)
                .filter(s -> s.getRoom().getId().equals(roomId))
                .orElseThrow(() -> new NotFoundException("Không tìm thấy ghế"));
        if (type == SeatType.COUPLE && !seat.getRowLabel().equals(lastRowLabel(seat.getRoom().getRowCount()))) {
            throw new BusinessException("Ghế đôi chỉ đặt ở hàng cuối cùng của phòng");
        }
        seat.setType(type);
        seat.setActive(active);
        return seat;
    }

    public boolean hasShowtimes(Long roomId) {
        return showtimeRepository.existsByRoomId(roomId);
    }

    static String lastRowLabel(int rowCount) {
        return String.valueOf((char) ('A' + rowCount - 1));
    }

    static List<String> rowLabels(int rowCount) {
        return java.util.stream.IntStream.range(0, rowCount)
                .mapToObj(i -> String.valueOf((char) ('A' + i)))
                .toList();
    }

    static Set<String> parseRows(String text) {
        if (text == null || text.isBlank()) {
            return new java.util.HashSet<>();
        }
        return Arrays.stream(text.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(java.util.HashSet::new));
    }
}
