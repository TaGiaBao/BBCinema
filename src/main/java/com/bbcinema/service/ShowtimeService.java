package com.bbcinema.service;

import com.bbcinema.config.AppProperties;
import com.bbcinema.dto.request.ShowtimeForm;
import com.bbcinema.dto.response.MovieSchedule;
import com.bbcinema.dto.response.SeatCell;
import com.bbcinema.dto.response.SeatMap;
import com.bbcinema.dto.response.SeatRow;
import com.bbcinema.entity.*;
import com.bbcinema.entity.enums.BookingStatus;
import com.bbcinema.entity.enums.MovieStatus;
import com.bbcinema.entity.enums.ScreenFormat;
import com.bbcinema.entity.enums.ShowLanguage;
import com.bbcinema.entity.enums.SeatStatus;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.exception.NotFoundException;
import com.bbcinema.repository.ShowtimeRepository;
import com.bbcinema.repository.ShowtimeSeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShowtimeService {

    private final ShowtimeRepository showtimeRepository;
    private final ShowtimeSeatRepository showtimeSeatRepository;
    private final MovieService movieService;
    private final RoomService roomService;
    private final PricingService pricingService;
    private final AppProperties properties;
    private final Clock clock;

    public Showtime getDetailed(Long id) {
        return showtimeRepository.findDetailedById(id)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy suất chiếu"));
    }

    /** Showtimes of a movie in a cinema on one day. On today's date, shows that already started are hidden. */
    public List<Showtime> findForMovie(Long movieId, Long cinemaId, LocalDate date) {
        return showtimeRepository.findForMovieInCinema(movieId, cinemaId, startOfVisibleDay(date), date.plusDays(1).atStartOfDay());
    }

    /** The day's schedule of a cinema, grouped by movie. */
    public List<MovieSchedule> scheduleForCinema(Long cinemaId, LocalDate date) {
        List<Showtime> showtimes = showtimeRepository.findForCinema(cinemaId, startOfVisibleDay(date), date.plusDays(1).atStartOfDay());
        Map<Movie, List<Showtime>> grouped = showtimes.stream()
                .collect(Collectors.groupingBy(Showtime::getMovie, LinkedHashMap::new, Collectors.toList()));
        return grouped.entrySet().stream().map(e -> new MovieSchedule(e.getKey(), e.getValue())).toList();
    }

    public Page<Showtime> search(Long cinemaId, Long movieId, LocalDate from, LocalDate to, Pageable pageable) {
        return showtimeRepository.search(cinemaId, movieId, from.atStartOfDay(), to.plusDays(1).atStartOfDay(), pageable);
    }

    /** Customers can see and book showtimes this many days ahead, today included. */
    public static final int BOOKING_WINDOW_DAYS = 14;

    public List<LocalDate> upcomingDates() {
        LocalDate today = LocalDate.now(clock);
        return java.util.stream.IntStream.range(0, BOOKING_WINDOW_DAYS).mapToObj(today::plusDays).toList();
    }

    public SeatMap seatMap(Long showtimeId) {
        Showtime showtime = getDetailed(showtimeId);
        List<Seat> layout = roomService.getSeats(showtime.getRoom().getId());
        Map<Long, ShowtimeSeat> bySeatId = showtimeSeatRepository.findSeatMap(showtimeId).stream()
                .collect(Collectors.toMap(ss -> ss.getSeat().getId(), ss -> ss));
        LocalDateTime now = LocalDateTime.now(clock);

        // Seats sit where the admin placed them on the room grid. Only seats on sale in this
        // showtime are drawn; aisles and empty grid lines stay as space.
        Map<Seat, SeatLayout.Position> positions = SeatLayout.positions(layout);
        int gridRows = SeatLayout.rows(layout, positions);
        List<List<SeatCell>> cellsByLine = new ArrayList<>();
        List<String> labelByLine = new ArrayList<>();
        for (int y = 0; y < gridRows; y++) {
            cellsByLine.add(new ArrayList<>());
            labelByLine.add("");
        }
        long available = 0;
        for (Seat seat : layout) {
            ShowtimeSeat ss = bySeatId.get(seat.getId());
            if (ss == null) {
                continue;
            }
            SeatStatus status = effectiveStatus(ss, now);
            if (status == SeatStatus.AVAILABLE) {
                available++;
            }
            SeatLayout.Position pos = positions.get(seat);
            cellsByLine.get(pos.y()).add(new SeatCell(ss.getId(), seat.getLabel(), seat.getNumber(), seat.getType(),
                    status, ss.getPrice(), pos.x(), seat.getWidth()));
            if (labelByLine.get(pos.y()).isEmpty()) {
                labelByLine.set(pos.y(), seat.getRowLabel());
            }
        }
        List<SeatRow> seatRows = new ArrayList<>();
        for (int y = 0; y < gridRows; y++) {
            List<SeatCell> cells = cellsByLine.get(y);
            cells.sort(java.util.Comparator.comparingInt(SeatCell::x));
            seatRows.add(new SeatRow(labelByLine.get(y), cells));
        }
        // Trim empty lines at the back (seats that are not on sale) so the map ends at the last seat.
        while (!seatRows.isEmpty() && seatRows.get(seatRows.size() - 1).cells().isEmpty()) {
            seatRows.remove(seatRows.size() - 1);
        }
        return new SeatMap(showtimeId, seatRows, SeatLayout.columns(layout, positions), available, bySeatId.size());
    }

    /** A hold that has run out counts as free even before the cleanup job releases it. */
    static SeatStatus effectiveStatus(ShowtimeSeat ss, LocalDateTime now) {
        if (ss.getStatus() == SeatStatus.HELD && ss.getHoldExpiresAt() != null && ss.getHoldExpiresAt().isBefore(now)) {
            return SeatStatus.AVAILABLE;
        }
        return ss.getStatus();
    }

    /**
     * Creates showtimes for every date in the range at every listed time. Each new showtime gets a
     * row per active seat with the price taken from the price table.
     *
     * @return the number of showtimes created
     */
    @Transactional
    public int createShowtimes(ShowtimeForm form) {
        Movie movie = movieService.getById(form.getMovieId());
        Room room = roomService.getById(form.getRoomId());
        if (movie.getStatus() == MovieStatus.ENDED) {
            throw new BusinessException("Phim đã ngừng chiếu, không thể tạo suất mới");
        }
        LocalDate to = form.getToDate() == null ? form.getFromDate() : form.getToDate();
        if (to.isBefore(form.getFromDate())) {
            throw new BusinessException("Ngày kết thúc phải sau ngày bắt đầu");
        }
        if (form.getFromDate().plusDays(31).isBefore(to)) {
            throw new BusinessException("Mỗi lần chỉ tạo tối đa 31 ngày");
        }
        List<LocalTime> times = parseTimes(form.getTimes());
        LocalDateTime now = LocalDateTime.now(clock);

        int created = 0;
        for (LocalDate date = form.getFromDate(); !date.isAfter(to); date = date.plusDays(1)) {
            for (LocalTime time : times) {
                LocalDateTime start = date.atTime(time);
                if (!start.isAfter(now)) {
                    throw new BusinessException("Suất " + format(start) + " đã ở trong quá khứ");
                }
                create(movie, room, start, form.getFormat(), form.getLanguage(), form.isSpecial());
                created++;
            }
        }
        return created;
    }

    @Transactional
    public Showtime create(Movie movie, Room room, LocalDateTime start, ScreenFormat format, boolean special) {
        return create(movie, room, start, format, null, special);
    }

    @Transactional
    public Showtime create(Movie movie, Room room, LocalDateTime start, ScreenFormat format, ShowLanguage language,
                           boolean special) {
        if (!room.isActive()) {
            throw new BusinessException("Phòng " + room.getName() + " đang tạm ngưng");
        }
        LocalDateTime end = start.plusMinutes(movie.getDurationMinutes() + (long) properties.booking().cleaningMinutes());
        List<Showtime> overlaps = showtimeRepository.findOverlapping(room.getId(), start, end, null);
        if (!overlaps.isEmpty()) {
            Showtime other = overlaps.get(0);
            throw new BusinessException("Suất " + format(start) + " bị trùng giờ với phim \""
                    + other.getMovie().getTitle() + "\" lúc " + format(other.getStartTime()) + " tại " + room.getName());
        }
        Showtime showtime = new Showtime();
        showtime.setMovie(movie);
        showtime.setRoom(room);
        showtime.setStartTime(start);
        showtime.setEndTime(end);
        showtime.setFormat(format);
        showtime.setLanguage(language != null ? language : ShowLanguage.defaultFor(movie.getLanguage()));
        showtime.setSpecial(special);
        showtime = showtimeRepository.save(showtime);

        List<ShowtimeSeat> seats = new ArrayList<>();
        for (Seat seat : roomService.getSeats(room.getId())) {
            if (!seat.isActive()) {
                continue;
            }
            ShowtimeSeat ss = new ShowtimeSeat();
            ss.setShowtime(showtime);
            ss.setSeat(seat);
            ss.setPrice(pricingService.priceFor(seat.getType(), format, start));
            seats.add(ss);
        }
        showtimeSeatRepository.saveAll(seats);
        return showtime;
    }

    /** Cancels a showtime that has not sold any ticket yet. */
    @Transactional
    public void cancel(Long showtimeId) {
        Showtime showtime = getDetailed(showtimeId);
        long sold = showtimeSeatRepository.countByShowtimeIdAndStatus(showtimeId, SeatStatus.BOOKED);
        long held = showtimeSeatRepository.countByShowtimeIdAndStatus(showtimeId, SeatStatus.HELD);
        if (sold > 0 || held > 0) {
            throw new BusinessException("Suất chiếu đã có " + (sold + held) + " ghế được đặt, không thể huỷ");
        }
        showtime.setCancelled(true);
    }

    public long countBooked(Long showtimeId) {
        return showtimeSeatRepository.countByShowtimeIdAndStatus(showtimeId, SeatStatus.BOOKED);
    }

    static List<LocalTime> parseTimes(String text) {
        List<LocalTime> times = new ArrayList<>();
        for (String part : text.split("[,;\\s]+")) {
            if (part.isBlank()) {
                continue;
            }
            try {
                String normalized = part.trim().replace('h', ':').replace('H', ':');
                if (normalized.endsWith(":")) {
                    normalized = normalized + "00";
                }
                if (normalized.indexOf(':') == 1) {
                    normalized = "0" + normalized;
                }
                times.add(LocalTime.parse(normalized));
            } catch (DateTimeParseException e) {
                throw new BusinessException("Giờ chiếu \"" + part + "\" không hợp lệ. Nhập dạng 19:30");
            }
        }
        if (times.isEmpty()) {
            throw new BusinessException("Vui lòng nhập ít nhất một giờ chiếu");
        }
        return times.stream().distinct().sorted().toList();
    }

    private LocalDateTime startOfVisibleDay(LocalDate date) {
        LocalDateTime now = LocalDateTime.now(clock);
        return date.equals(now.toLocalDate()) ? now : date.atStartOfDay();
    }

    private static String format(LocalDateTime time) {
        return String.format("%02d:%02d %02d/%02d", time.getHour(), time.getMinute(), time.getDayOfMonth(), time.getMonthValue());
    }

}
