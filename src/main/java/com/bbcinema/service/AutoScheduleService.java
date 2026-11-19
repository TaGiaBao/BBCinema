package com.bbcinema.service;

import com.bbcinema.config.AppProperties;
import com.bbcinema.entity.Movie;
import com.bbcinema.entity.Room;
import com.bbcinema.entity.Showtime;
import com.bbcinema.entity.enums.AgeRating;
import com.bbcinema.entity.enums.ScreenFormat;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.repository.MovieRepository;
import com.bbcinema.repository.ShowtimeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Proposes a schedule for one cinema over a few days. Each room is filled from the opening time:
 * at every free moment the movie with the best score for that slot is placed, its show ends, the room
 * is cleaned, and the next slot starts. The score combines five things:
 * <ol>
 *   <li>day of the week: weekends lift every slot, most of all the daytime ones;</li>
 *   <li>running time: a movie must end before closing, so long movies drop out late at night;</li>
 *   <li>golden hour: the best movies get the evening slots people want most;</li>
 *   <li>rating: member ratings, plus a bonus for movies released in the last two weeks;</li>
 *   <li>genre: family and animation in the daytime, horror in the evening, 18+ never in the morning.</li>
 * </ol>
 * A movie loses points each time it is already on the day's schedule, and right after itself in the
 * same room, so the program stays varied. Existing showtimes are kept and worked around. The result is
 * only a proposal; {@link #save} creates the showtimes the admin keeps.
 */
@Service
@RequiredArgsConstructor
public class AutoScheduleService {

    public static final int MAX_DAYS = 14;
    private static final int ROUND_TO_MINUTES = 5;
    private static final Set<String> DAYTIME_GENRES = Set.of("hoạt hình", "gia đình", "học đường");
    private static final Set<String> EVENING_GENRES = Set.of("kinh dị", "hồi hộp", "hình sự");

    private final ShowtimeRepository showtimeRepository;
    private final MovieRepository movieRepository;
    private final RoomService roomService;
    private final ShowtimeService showtimeService;
    private final AppProperties properties;
    private final Clock clock;

    /** What the admin asks for. */
    public record Request(Long cinemaId, LocalDate from, LocalDate to, LocalTime open, LocalTime lastStart,
                          LocalTime goldenFrom, LocalTime goldenTo, List<Long> movieIds) {
    }

    /** One proposed showtime and the score that won it the slot. */
    public record Planned(Movie movie, Room room, LocalDateTime start, LocalDateTime end, ScreenFormat format,
                          double score, boolean golden) {
    }

    @Transactional(readOnly = true)
    public List<Planned> plan(Request request) {
        validate(request);
        List<Movie> movies = movieRepository.findAllById(request.movieIds()).stream()
                .sorted(Comparator.comparing(Movie::getId)).toList();
        if (movies.isEmpty()) {
            throw new BusinessException("Vui lòng chọn ít nhất một phim");
        }
        List<Room> rooms = roomService.findActiveByCinema(request.cinemaId());
        if (rooms.isEmpty()) {
            throw new BusinessException("Rạp chưa có phòng chiếu đang hoạt động");
        }
        int cleaning = properties.booking().cleaningMinutes();
        LocalDateTime notBefore = LocalDateTime.now(clock).plusMinutes(30);
        List<Planned> plan = new ArrayList<>();

        for (LocalDate day = request.from(); !day.isAfter(request.to()); day = day.plusDays(1)) {
            Map<Long, Integer> shownToday = new HashMap<>();
            for (Room room : rooms) {
                List<Showtime> existing = showtimeRepository.findOverlapping(room.getId(),
                        day.atTime(request.open()), day.plusDays(1).atTime(4, 0), null);
                existing.forEach(s -> shownToday.merge(s.getMovie().getId(), 1, Integer::sum));
                ScreenFormat format = room.getName().toUpperCase(Locale.ROOT).contains("3D") ? ScreenFormat.THREE_D : ScreenFormat.TWO_D;
                LocalDateTime t = roundUp(day.atTime(request.open()));
                if (t.isBefore(notBefore)) {
                    t = roundUp(notBefore);
                }
                Long previousMovie = null;
                LocalDateTime lastStart = day.atTime(request.lastStart());
                while (!t.isAfter(lastStart)) {
                    Showtime blocking = firstOverlap(existing, t, t.plusMinutes(1));
                    if (blocking != null) {
                        t = roundUp(blocking.getEndTime());
                        previousMovie = blocking.getMovie().getId();
                        continue;
                    }
                    Planned best = null;
                    for (Movie movie : movies) {
                        LocalDateTime end = t.plusMinutes(movie.getDurationMinutes() + (long) cleaning);
                        if (end.isAfter(day.plusDays(1).atTime(1, 0)) || firstOverlap(existing, t, end) != null) {
                            continue; // would run past ~01:00 or into an existing showtime
                        }
                        double score = score(movie, t, request, shownToday.getOrDefault(movie.getId(), 0),
                                movie.getId().equals(previousMovie));
                        if (best == null || score > best.score()) {
                            best = new Planned(movie, room, t, end, format, score, isGolden(t, request));
                        }
                    }
                    if (best == null) {
                        t = roundUp(t.plusMinutes(ROUND_TO_MINUTES)); // nothing fits here; try a bit later
                        continue;
                    }
                    plan.add(best);
                    shownToday.merge(best.movie().getId(), 1, Integer::sum);
                    previousMovie = best.movie().getId();
                    t = roundUp(best.end());
                }
            }
        }
        plan.sort(Comparator.comparing(Planned::start).thenComparing(p -> p.room().getName()));
        return plan;
    }

    /** Creates the proposed showtimes; ones that now clash with another showtime are skipped. */
    @Transactional
    public int save(Request request) {
        int created = 0;
        for (Planned p : plan(request)) {
            try {
                showtimeService.create(p.movie(), p.room(), p.start(), p.format(), false);
                created++;
            } catch (BusinessException clash) {
                // Someone added a showtime in the meantime: keep theirs.
            }
        }
        return created;
    }

    /** Higher is better. See the class comment for the five factors. */
    double score(Movie movie, LocalDateTime start, Request request, int alreadyToday, boolean sameAsPrevious) {
        LocalTime time = start.toLocalTime();
        boolean weekend = start.getDayOfWeek() == DayOfWeek.SATURDAY || start.getDayOfWeek() == DayOfWeek.SUNDAY;
        boolean golden = isGolden(start, request);
        boolean daytime = time.isBefore(LocalTime.of(17, 0));

        // Rating (4) and freshness: 1.0 to 2.0 from stars (3 stars if not rated yet), +0.4 if just released.
        double stars = movie.getRatingCount() > 0 && movie.getRatingAverage() != null ? movie.getRatingAverage() : 3.0;
        double appeal = 1 + stars / 5;
        if (movie.getReleaseDate() != null) {
            long age = ChronoUnit.DAYS.between(movie.getReleaseDate(), start.toLocalDate());
            if (age >= 0 && age <= 14) {
                appeal += 0.4;
            }
        }

        // Golden hour (3): the better a movie, the more it gains from an evening slot.
        double timeWeight = golden ? 1 + 0.6 * (appeal - 1) : (daytime ? 0.85 : 1.0);
        // Day of the week (1).
        if (weekend) {
            timeWeight *= daytime ? 1.25 : 1.1;
        }

        // Genre and age rating (5).
        Set<String> genres = movie.getGenres().stream().map(g -> g.getName().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        double genreWeight = 1;
        if (genres.stream().anyMatch(DAYTIME_GENRES::contains)) {
            genreWeight *= daytime ? 1.35 : (time.isAfter(LocalTime.of(20, 0)) ? 0.6 : 0.9);
        }
        if (genres.stream().anyMatch(EVENING_GENRES::contains)) {
            genreWeight *= time.isBefore(LocalTime.of(12, 0)) ? 0.5 : (time.isBefore(LocalTime.of(18, 0)) ? 0.8 : 1.3);
        }
        if (movie.getAgeRating() == AgeRating.T18 && time.isBefore(LocalTime.of(12, 0))) {
            genreWeight *= 0.4;
        }

        // Running time (2): short films fit more shows into the day, so give them a small edge.
        double lengthWeight = 1 + Math.max(0, 120 - movie.getDurationMinutes()) / 400.0;

        // Variety: each earlier show of this movie today costs 15%, back-to-back in a room 50%.
        double variety = Math.pow(0.85, alreadyToday) * (sameAsPrevious ? 0.5 : 1);
        return appeal * timeWeight * genreWeight * lengthWeight * variety;
    }

    private static boolean isGolden(LocalDateTime start, Request request) {
        LocalTime time = start.toLocalTime();
        return !time.isBefore(request.goldenFrom()) && time.isBefore(request.goldenTo());
    }

    private static Showtime firstOverlap(List<Showtime> existing, LocalDateTime start, LocalDateTime end) {
        return existing.stream()
                .filter(s -> !s.isCancelled() && s.getStartTime().isBefore(end) && s.getEndTime().isAfter(start))
                .min(Comparator.comparing(Showtime::getStartTime)).orElse(null);
    }

    private static LocalDateTime roundUp(LocalDateTime t) {
        LocalDateTime base = t.withSecond(0).withNano(0);
        int extra = (ROUND_TO_MINUTES - base.getMinute() % ROUND_TO_MINUTES) % ROUND_TO_MINUTES;
        if (extra == 0 && (t.getSecond() > 0 || t.getNano() > 0)) {
            extra = ROUND_TO_MINUTES;
        }
        return base.plusMinutes(extra);
    }

    private static void validate(Request r) {
        if (r.cinemaId() == null || r.from() == null || r.to() == null) {
            throw new BusinessException("Vui lòng chọn rạp và khoảng ngày");
        }
        if (r.to().isBefore(r.from())) {
            throw new BusinessException("Ngày kết thúc phải sau ngày bắt đầu");
        }
        if (ChronoUnit.DAYS.between(r.from(), r.to()) >= MAX_DAYS) {
            throw new BusinessException("Mỗi lần chỉ xếp lịch tối đa " + MAX_DAYS + " ngày");
        }
        if (r.open() == null || r.lastStart() == null || !r.lastStart().isAfter(r.open())) {
            throw new BusinessException("Giờ suất cuối phải sau giờ mở cửa");
        }
        if (r.goldenFrom() == null || r.goldenTo() == null || !r.goldenTo().isAfter(r.goldenFrom())) {
            throw new BusinessException("Khung giờ vàng không hợp lệ");
        }
    }
}
