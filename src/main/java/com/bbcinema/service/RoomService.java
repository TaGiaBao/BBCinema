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
import com.bbcinema.repository.ShowtimeSeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
    private final ShowtimeSeatRepository showtimeSeatRepository;
    private final Clock clock;

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
     * Rows listed in vipRows become VIP and rows in coupleRows become couple seats. Every seat gets
     * its grid position; the admin can then rearrange the room freely with the layout editor.
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

        Room room = new Room();
        room.setCinema(cinema);
        room.setName(form.getName().trim());
        room.setRowCount(form.getRowCount());
        room.setSeatsPerRow(form.getSeatsPerRow());
        List<String> rows = rowLabels(form.getRowCount());
        for (int y = 0; y < rows.size(); y++) {
            String row = rows.get(y);
            SeatType type = vipRows.contains(row) ? SeatType.VIP
                    : coupleRows.contains(row) ? SeatType.COUPLE : SeatType.STANDARD;
            // A couple seat is as wide as two normal seats, so a couple row holds half as many,
            // centred under the other rows.
            int width = type == SeatType.COUPLE ? 2 : 1;
            int seatsInRow = type == SeatType.COUPLE ? Math.max(1, form.getSeatsPerRow() / 2) : form.getSeatsPerRow();
            int offset = Math.max(0, (form.getSeatsPerRow() - seatsInRow * width) / 2);
            for (int number = 1; number <= seatsInRow; number++) {
                Seat seat = new Seat(row, number, type);
                seat.setGridX(offset + (number - 1) * width);
                seat.setGridY(y);
                room.addSeat(seat);
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

    // ------------------------------------------------------------------ layout editor

    /** One seat as the layout editor sends it. {@code id} is null for a seat the admin added. */
    public record LayoutSeat(Long id, String row, int number, SeatType type, boolean active, int x, int y) {
    }

    /** What the editor needs to know about a room: its seats and which of them it must protect. */
    public record LayoutView(List<LayoutSeat> seats, Set<Long> usedSeatIds, Set<Long> lockedSeatIds) {
    }

    public LayoutView layout(Long roomId) {
        List<Seat> seats = getSeats(roomId);
        Map<Seat, SeatLayout.Position> positions = SeatLayout.positions(seats);
        List<LayoutSeat> items = seats.stream()
                .map(s -> new LayoutSeat(s.getId(), s.getRowLabel(), s.getNumber(), s.getType(), s.isActive(),
                        positions.get(s).x(), positions.get(s).y()))
                .toList();
        return new LayoutView(items, showtimeSeatRepository.findSeatIdsUsedInRoom(roomId),
                showtimeSeatRepository.findSeatIdsTakenInRoom(roomId, LocalDateTime.now(clock)));
    }

    /**
     * Replaces the seat layout of a room with what the editor sent: seats are moved, added, renamed,
     * retyped or removed in one go.
     * <ul>
     *   <li>Seats may not overlap (a couple seat takes two cells) and names must be unique.</li>
     *   <li>A seat that has been in any showtime cannot be removed, because tickets point to it;
     *       turn it into an aisle (inactive) instead.</li>
     *   <li>A seat that is held or sold for a showtime that has not ended keeps its name.</li>
     * </ul>
     * Like other seat changes, types and active flags apply to showtimes created afterwards.
     */
    @Transactional
    public Room saveLayout(Long roomId, List<LayoutSeat> items) {
        Room room = getById(roomId);
        Map<Long, Seat> existing = getSeats(roomId).stream().collect(Collectors.toMap(Seat::getId, s -> s));
        validateLayout(items, existing.keySet());

        Set<Long> kept = items.stream().map(LayoutSeat::id).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> used = showtimeSeatRepository.findSeatIdsUsedInRoom(roomId);
        List<String> notRemovable = existing.values().stream()
                .filter(s -> !kept.contains(s.getId()) && used.contains(s.getId()))
                .map(Seat::getLabel).sorted().toList();
        if (!notRemovable.isEmpty()) {
            throw new BusinessException("Không xoá được ghế " + String.join(", ", notRemovable)
                    + " vì đã có suất chiếu dùng ghế này. Hãy chuyển chúng thành lối đi.");
        }
        Set<Long> locked = showtimeSeatRepository.findSeatIdsTakenInRoom(roomId, LocalDateTime.now(clock));
        for (LayoutSeat item : items) {
            Seat seat = item.id() == null ? null : existing.get(item.id());
            if (seat != null && locked.contains(seat.getId()) && renamed(seat, item)) {
                throw new BusinessException("Ghế " + seat.getLabel() + " đã có khách giữ hoặc mua cho suất sắp chiếu, "
                        + "không đổi tên được");
            }
        }

        // Remove deleted seats, then park the renamed ones on temporary numbers so that swapping two
        // names never hits UNIQUE(room_id, row_label, number) half-way through the update.
        existing.values().stream().filter(s -> !kept.contains(s.getId())).forEach(s -> {
            room.getSeats().remove(s);
            seatRepository.delete(s);
        });
        int temp = -1;
        for (LayoutSeat item : items) {
            Seat seat = item.id() == null ? null : existing.get(item.id());
            if (seat != null && renamed(seat, item)) {
                seat.setNumber(temp--);
            }
        }
        seatRepository.flush();

        for (LayoutSeat item : items) {
            Seat seat = item.id() == null ? null : existing.get(item.id());
            if (seat == null) {
                seat = new Seat(normalizeRow(item.row()), item.number(), item.type());
                room.addSeat(seat);
            }
            seat.setRowLabel(normalizeRow(item.row()));
            seat.setNumber(item.number());
            seat.setType(item.type());
            seat.setActive(item.active());
            seat.setGridX(item.x());
            seat.setGridY(item.y());
        }
        room.setRowCount(items.stream().mapToInt(i -> i.y() + 1).max().orElse(0));
        room.setSeatsPerRow(items.stream().mapToInt(i -> i.x() + widthOf(i.type())).max().orElse(0));
        return roomRepository.save(room);
    }

    private static boolean renamed(Seat seat, LayoutSeat item) {
        return !seat.getRowLabel().equals(normalizeRow(item.row())) || seat.getNumber() != item.number();
    }

    static void validateLayout(List<LayoutSeat> items, Set<Long> roomSeatIds) {
        if (items.isEmpty()) {
            throw new BusinessException("Phòng chiếu phải có ít nhất một ghế");
        }
        Map<String, String> cells = new HashMap<>();
        Set<String> names = new HashSet<>();
        Set<Long> ids = new HashSet<>();
        for (LayoutSeat item : items) {
            if (item.id() != null && (!roomSeatIds.contains(item.id()) || !ids.add(item.id()))) {
                throw new BusinessException("Dữ liệu sơ đồ không hợp lệ (ghế #" + item.id() + ")");
            }
            if (item.type() == null) {
                throw new BusinessException("Thiếu loại ghế");
            }
            String row = normalizeRow(item.row());
            if (!row.matches("[A-Z]{1,2}") || item.number() < 1 || item.number() > 99) {
                throw new BusinessException("Tên ghế \"" + item.row() + item.number()
                        + "\" không hợp lệ: hàng là 1-2 chữ cái, số ghế từ 1 đến 99");
            }
            String name = row + item.number();
            if (!names.add(name)) {
                throw new BusinessException("Có hai ghế cùng tên " + name + ". Hãy đổi tên hoặc đánh số lại.");
            }
            int width = widthOf(item.type());
            if (item.x() < 0 || item.y() < 0 || item.x() + width > SeatLayout.MAX_COLUMNS
                    || item.y() >= SeatLayout.MAX_ROWS) {
                throw new BusinessException("Ghế " + name + " nằm ngoài sơ đồ (tối đa " + SeatLayout.MAX_COLUMNS
                        + " cột, " + SeatLayout.MAX_ROWS + " hàng)");
            }
            for (int dx = 0; dx < width; dx++) {
                String other = cells.put((item.x() + dx) + ":" + item.y(), name);
                if (other != null) {
                    throw new BusinessException("Ghế " + name + " chồng lên ghế " + other);
                }
            }
        }
    }

    private static int widthOf(SeatType type) {
        return type == SeatType.COUPLE ? 2 : 1;
    }

    private static String normalizeRow(String row) {
        return row == null ? "" : row.trim().toUpperCase(Locale.ROOT);
    }

    public boolean hasShowtimes(Long roomId) {
        return showtimeRepository.existsByRoomId(roomId);
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
