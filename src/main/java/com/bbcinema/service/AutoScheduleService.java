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
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Proposes a schedule for one cinema, the way a cinema operations manager builds one.
 * <ol>
 *   <li><b>Turnaround.</b> A show holds its room for trailers + the movie + cleaning, and cleaning grows with the
 *       room: {@code 10 + ceil(seats / 100) * 5} minutes.</li>
 *   <li><b>Movie tiers.</b> Tier 1 = released in the last two weeks, tier 2 = weeks three and four, tier 3 = older.
 *       Well-rated movies move up a tier, poorly rated ones down; the admin can override a tier. Each tier aims at
 *       a share of the day's shows (45 / 35 / 20 %), split between its movies.</li>
 *   <li><b>Dayparts.</b> Morning favours family films and tier 3, the afternoon tiers 1 and 2, prime time tier 1
 *       (largest rooms first), late night horror, thrillers and 18+. 18+ films never start before noon.</li>
 *   <li><b>Prime-time anchor.</b> Each room first gets a show at a fixed prime-time anchor (e.g. 19:00, then 19:15
 *       in the next room); the evening is filled forwards from it and the day backwards, so prime time is never
 *       "missed" by a show that happens to end at 18:50.</li>
 *   <li><b>Staggering.</b> Two rooms never start within 10 minutes of each other (lobby and ticket check), and two
 *       shows of the same movie in different rooms are kept 45 minutes apart.</li>
 * </ol>
 * Start times are rounded to 5 minutes. Existing showtimes are kept (they act as locked slots) and the plan fills
 * around them. The result is only a proposal; {@link #save} creates the showtimes the admin keeps.
 */
@Service
@RequiredArgsConstructor
public class AutoScheduleService {

    public static final int MAX_DAYS = 14;
    public static final int DEFAULT_TRAILER_MINUTES = 10;
    /** Target share of the day's shows for tiers 1, 2 and 3. */
    static final double[] TIER_SHARE = {0.45, 0.35, 0.20};
    static final int STAGGER_MINUTES = 10;
    static final int ANCHOR_STEP_MINUTES = 15;
    /** How far a start may move to keep the lobby staggered before the clash is accepted (with a penalty). */
    static final int MAX_STAGGER_SHIFT = 30;
    static final int SAME_MOVIE_GAP_MINUTES = 45;
    private static final int ROUND_TO_MINUTES = 5;
    private static final LocalTime NOON = LocalTime.NOON;
    private static final Set<String> DAYTIME_GENRES = Set.of("hoạt hình", "gia đình", "học đường");
    private static final Set<String> LATE_GENRES = Set.of("kinh dị", "hồi hộp", "hình sự", "hành động");

    private final ShowtimeRepository showtimeRepository;
    private final MovieRepository movieRepository;
    private final RoomService roomService;
    private final ShowtimeService showtimeService;
    private final AppProperties properties;
    private final Clock clock;

    /**
     * What the admin asks for. {@code tiers} maps a movie id to a tier (1-3) chosen by the admin; movies not in
     * the map get their tier automatically.
     */
    public record Request(Long cinemaId, LocalDate from, LocalDate to, LocalTime open, LocalTime lastStart,
                          LocalTime goldenFrom, LocalTime goldenTo, List<Long> movieIds,
                          int trailerMinutes, Map<Long, Integer> tiers) {

        public Request {
            movieIds = movieIds == null ? List.of() : movieIds;
            tiers = tiers == null ? Map.of() : tiers;
        }

        public Request(Long cinemaId, LocalDate from, LocalDate to, LocalTime open, LocalTime lastStart,
                       LocalTime goldenFrom, LocalTime goldenTo, List<Long> movieIds) {
            this(cinemaId, from, to, open, lastStart, goldenFrom, goldenTo, movieIds, DEFAULT_TRAILER_MINUTES, Map.of());
        }
    }

    /**
     * One proposed showtime. {@code end} is when the movie ends; the room is free again at {@code roomFree},
     * after cleaning.
     */
    public record Planned(Movie movie, Room room, LocalDateTime start, LocalDateTime end, LocalDateTime roomFree,
                          ScreenFormat format, int tier, double score, boolean golden) {
    }

    /** Per movie totals of a plan, so the admin can see whether the shares came out even. */
    public record MovieShare(Movie movie, int tier, String tiers, int shows, int goldenShows, double percent) {
    }

    @Transactional(readOnly = true)
    public List<Planned> plan(Request request) {
        validate(request);
        List<Movie> movies = movieRepository.findAllById(request.movieIds()).stream()
                .sorted(Comparator.comparing(Movie::getId)).toList();
        if (movies.isEmpty()) {
            throw new BusinessException("Vui lòng chọn ít nhất một phim");
        }
        List<Room> rooms = new ArrayList<>(roomService.findActiveByCinema(request.cinemaId()));
        if (rooms.isEmpty()) {
            throw new BusinessException("Rạp chưa có phòng chiếu đang hoạt động");
        }
        // Largest rooms first: they get the earliest prime-time anchor and the strongest movies.
        rooms.sort(Comparator.comparingInt(AutoScheduleService::capacity).reversed().thenComparing(Room::getName));
        List<Planned> plan = new ArrayList<>();
        for (LocalDate day = request.from(); !day.isAfter(request.to()); day = day.plusDays(1)) {
            LocalDate d = day;
            // A movie is only scheduled from its release date (early screenings are added by hand as specials).
            List<Movie> released = movies.stream()
                    .filter(m -> m.getReleaseDate() == null || !m.getReleaseDate().isAfter(d)).toList();
            if (!released.isEmpty()) {
                plan.addAll(new DayPlanner(request, day, released, rooms).run());
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
                showtimeService.create(p.movie(), p.room(), p.start(), p.format(), null, false, p.roomFree());
                created++;
            } catch (BusinessException clash) {
                // Someone added a showtime in the meantime: keep theirs.
            }
        }
        return created;
    }

    /** How many shows each movie got in the plan, and its share of all proposed shows. */
    public static List<MovieShare> shares(List<Planned> plan) {
        Map<Movie, List<Planned>> byMovie = new LinkedHashMap<>();
        plan.forEach(p -> byMovie.computeIfAbsent(p.movie(), m -> new ArrayList<>()).add(p));
        return byMovie.values().stream()
                .map(list -> new MovieShare(list.get(0).movie(),
                        list.stream().mapToInt(Planned::tier).min().orElse(2),
                        list.stream().map(Planned::tier).distinct().sorted().map(String::valueOf).collect(Collectors.joining("–")),
                        list.size(),
                        (int) list.stream().filter(Planned::golden).count(), 100.0 * list.size() / plan.size()))
                .sorted(Comparator.comparingInt(MovieShare::tier).thenComparing(MovieShare::shows, Comparator.reverseOrder()))
                .toList();
    }

    // ------------------------------------------------------------------ formulas

    /** Cleaning and checking a room after a show: 10 minutes plus 5 per started hundred seats. */
    public static int cleaningMinutes(int seats) {
        return 10 + (int) Math.ceil(Math.max(seats, 1) / 100.0) * 5;
    }

    static int capacity(Room room) {
        return room.getSeats().isEmpty() ? room.getRowCount() * room.getSeatsPerRow() : room.getSeats().size();
    }

    /**
     * Tier 1 to 3 from the weeks since release, moved up one tier for a rating of 4.2 or more and down one
     * below 3.0 (only once at least three members rated it).
     */
    public static int autoTier(Movie movie, LocalDate day) {
        int tier = 2;
        if (movie.getReleaseDate() != null) {
            long days = ChronoUnit.DAYS.between(movie.getReleaseDate(), day);
            tier = days < 14 ? 1 : (days < 28 ? 2 : 3);
        }
        if (movie.getRatingCount() >= 3 && movie.getRatingAverage() != null) {
            if (movie.getRatingAverage() >= 4.2) {
                tier--;
            } else if (movie.getRatingAverage() < 3.0) {
                tier++;
            }
        }
        return Math.max(1, Math.min(3, tier));
    }

    /**
     * Shows each movie should get: the day's estimated slot count split by tier share, then equally inside a
     * tier. Shares of tiers without movies are spread over the others, and every movie aims at one show at least.
     */
    static Map<Long, Double> targets(Map<Long, Integer> tierOf, double totalSlots) {
        int[] perTier = new int[3];
        tierOf.values().forEach(t -> perTier[t - 1]++);
        double used = 0;
        for (int t = 0; t < 3; t++) {
            if (perTier[t] > 0) {
                used += TIER_SHARE[t];
            }
        }
        Map<Long, Double> targets = new HashMap<>();
        for (Map.Entry<Long, Integer> e : tierOf.entrySet()) {
            int t = e.getValue() - 1;
            targets.put(e.getKey(), Math.max(1.0, totalSlots * TIER_SHARE[t] / used / perTier[t]));
        }
        return targets;
    }

    // ------------------------------------------------------------------ one day

    private final class DayPlanner {
        private final Request request;
        private final LocalDate day;
        private final List<Movie> movies;
        private final List<Room> rooms;
        private final int maxCapacity;
        private final boolean weekend;
        private final LocalDateTime earliest;
        private final LocalDateTime lastStart;
        private final LocalDateTime latestEnd;
        private final Map<Long, Integer> tierOf = new HashMap<>();
        private final Map<Long, Double> target;
        private final Map<Long, Integer> shown = new HashMap<>();
        private final Map<Long, List<LocalDateTime>> movieStarts = new HashMap<>();
        private final List<LocalDateTime> allStarts = new ArrayList<>();
        private final Map<Long, List<Showtime>> existing = new HashMap<>();
        private final List<Planned> planned = new ArrayList<>();

        DayPlanner(Request request, LocalDate day, List<Movie> movies, List<Room> rooms) {
            this.request = request;
            this.day = day;
            this.movies = movies;
            this.rooms = rooms;
            this.maxCapacity = rooms.stream().mapToInt(AutoScheduleService::capacity).max().orElse(1);
            this.weekend = day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY;
            LocalDateTime notBefore = LocalDateTime.now(clock).plusMinutes(30);
            LocalDateTime open = day.atTime(request.open());
            this.earliest = roundUp(open.isBefore(notBefore) ? notBefore : open);
            this.lastStart = day.atTime(request.lastStart());
            this.latestEnd = day.plusDays(1).atTime(1, 0);
            for (Movie m : movies) {
                Integer chosen = request.tiers().get(m.getId());
                tierOf.put(m.getId(), chosen != null && chosen >= 1 && chosen <= 3 ? chosen : autoTier(m, day));
            }
            for (Room room : rooms) {
                List<Showtime> list = showtimeRepository.findOverlapping(room.getId(), day.atStartOfDay(),
                                day.plusDays(1).atTime(4, 0), null).stream()
                        .filter(s -> !s.isCancelled())
                        .sorted(Comparator.comparing(Showtime::getStartTime)).toList();
                existing.put(room.getId(), list);
                for (Showtime s : list) {
                    if (s.getStartTime().toLocalDate().equals(day)) {
                        record(s.getMovie().getId(), s.getStartTime());
                    }
                }
            }
            this.target = targets(tierOf, estimatedSlots());
        }

        /** N = floor((last start - open) / average turnaround) + 1 per room, summed over the rooms. */
        private double estimatedSlots() {
            double avgMovie = movies.stream().mapToInt(Movie::getDurationMinutes).average().orElse(110);
            double minutes = Math.max(0, Duration.between(earliest, lastStart).toMinutes());
            double slots = 0;
            for (Room room : rooms) {
                double turnaround = request.trailerMinutes() + avgMovie + cleaningMinutes(capacity(room));
                slots += Math.floor(minutes / turnaround) + 1;
            }
            return slots;
        }

        List<Planned> run() {
            // 1. Prime time first: every room gets its anchor, then the evening is filled forwards from it.
            List<Runnable> mornings = new ArrayList<>();
            for (int i = 0; i < rooms.size(); i++) {
                Room room = rooms.get(i);
                LocalDateTime anchor = anchorFor(i);
                for (LocalDateTime[] gap : freeGaps(room)) {
                    LocalDateTime from = gap[0];
                    LocalDateTime to = gap[1];
                    if (!anchor.isBefore(from) && anchor.isBefore(to)) {
                        LocalDateTime firstStart = forward(room, anchor, to);
                        mornings.add(() -> backward(room, from, firstStart));
                    } else {
                        mornings.add(() -> forward(room, from, to));
                    }
                }
            }
            // 2. Then the rest of the day, working back from prime time (and any gaps without an anchor).
            mornings.forEach(Runnable::run);
            return planned;
        }

        /** Prime-time anchor of the i-th largest room: an hour into prime time, 15 minutes later per room. */
        private LocalDateTime anchorFor(int i) {
            LocalDateTime goldenFrom = day.atTime(request.goldenFrom());
            long goldenMinutes = Math.max(ROUND_TO_MINUTES, Duration.between(goldenFrom, day.atTime(request.goldenTo())).toMinutes());
            long offset = Math.min(60, goldenMinutes / 3) + (long) i * ANCHOR_STEP_MINUTES;
            LocalDateTime anchor = roundUp(goldenFrom.plusMinutes(offset % goldenMinutes));
            return anchor.isAfter(lastStart) ? roundDown(lastStart) : anchor;
        }

        /** Free stretches of a room between its existing showtimes, from opening until 01:00. */
        private List<LocalDateTime[]> freeGaps(Room room) {
            List<LocalDateTime[]> gaps = new ArrayList<>();
            LocalDateTime cursor = earliest;
            for (Showtime s : existing.get(room.getId())) {
                if (s.getStartTime().isAfter(cursor)) {
                    gaps.add(new LocalDateTime[]{cursor, s.getStartTime()});
                }
                if (s.getEndTime().isAfter(cursor)) {
                    cursor = roundUp(s.getEndTime());
                }
            }
            if (cursor.isBefore(latestEnd)) {
                gaps.add(new LocalDateTime[]{cursor, latestEnd});
            }
            return gaps;
        }

        /**
         * Fills a gap from {@code t} (the prime-time anchor, or the start of a gap) towards its end.
         * Returns when the first placed show starts, or the gap end if nothing fits.
         */
        private LocalDateTime forward(Room room, LocalDateTime t, LocalDateTime gapEnd) {
            LocalDateTime first = null;
            Long previous = null;
            t = roundUp(t);
            while (!t.isAfter(lastStart)) {
                LocalDateTime start = staggerLater(t); // normally the anchor itself, anchors are 15 minutes apart
                Candidate best = null;
                for (Movie m : movies) {
                    Candidate c = candidate(room, m, start, previous);
                    if (c != null && !c.roomFree.isAfter(gapEnd) && (best == null || c.score > best.score)) {
                        best = c;
                    }
                }
                if (best == null) {
                    t = t.plusMinutes(ROUND_TO_MINUTES);
                    continue;
                }
                place(best);
                first = first == null ? best.start : first;
                previous = best.movie.getId();
                t = roundUp(best.roomFree);
            }
            return first == null ? gapEnd : first;
        }

        /** Fills a gap backwards: each show must leave the room clean by {@code end}. */
        private void backward(Room room, LocalDateTime gapStart, LocalDateTime end) {
            Long next = null;
            for (int guard = 0; guard < 50; guard++) {
                Candidate best = null;
                for (Movie m : movies) {
                    LocalDateTime start = staggerEarlier(roundDown(end.minusMinutes(turnaround(m, room))));
                    if (start.isBefore(gapStart)) {
                        continue;
                    }
                    Candidate c = candidate(room, m, start, next);
                    if (c != null && !c.roomFree.isAfter(end) && (best == null || c.score > best.score)) {
                        best = c;
                    }
                }
                if (best == null) {
                    return;
                }
                place(best);
                next = best.movie.getId();
                end = best.start;
            }
        }

        private Candidate candidate(Room room, Movie movie, LocalDateTime start, Long neighbour) {
            LocalDateTime movieEnd = start.plusMinutes(request.trailerMinutes() + (long) movie.getDurationMinutes());
            LocalDateTime roomFree = movieEnd.plusMinutes(cleaningMinutes(capacity(room)));
            if (start.isAfter(lastStart) || movieEnd.isAfter(latestEnd)) {
                return null;
            }
            if (movie.getAgeRating() == AgeRating.T18 && start.toLocalTime().isBefore(NOON)) {
                return null; // 18+ never in the morning
            }
            double score = score(movie, room, start, neighbour);
            return new Candidate(movie, room, start, movieEnd, roomFree, score);
        }

        private int turnaround(Movie movie, Room room) {
            return request.trailerMinutes() + movie.getDurationMinutes() + cleaningMinutes(capacity(room));
        }

        /** Higher is better: daypart fit x room fit x how far the movie is below its share x spacing. */
        private double score(Movie movie, Room room, LocalDateTime start, Long neighbour) {
            int tier = tierOf.get(movie.getId());
            LocalTime time = start.toLocalTime();
            Set<String> genres = movie.getGenres().stream().map(g -> g.getName().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
            boolean family = genres.stream().anyMatch(DAYTIME_GENRES::contains);
            boolean late = genres.stream().anyMatch(LATE_GENRES::contains)
                    || movie.getAgeRating() == AgeRating.T18 || movie.getAgeRating() == AgeRating.T16;
            double sizeRatio = (double) capacity(room) / maxCapacity;

            double fit = switch (daypart(time)) {
                case MORNING -> (family ? 1.4 : 1) * (tier == 3 ? 1.2 : 1) * (late ? 0.6 : 1)
                        // A tier 1 movie in the morning goes to a smaller room (screen swapping).
                        * (tier == 1 ? 1.1 - 0.3 * sizeRatio : 1);
                case AFTERNOON -> (family ? 1.2 : 1) * (tier <= 2 ? 1.1 : 0.9);
                case PRIME -> (tier == 1 ? 1.5 * (0.7 + 0.6 * sizeRatio) : tier == 2 ? 1.15 : 0.5 * (1.3 - 0.6 * sizeRatio))
                        * (family && time.isAfter(LocalTime.of(20, 0)) ? 0.6 : 1);
                case LATE -> (late ? 1.4 : 1) * (family ? 0.4 : 1);
            };
            if (weekend && family && time.isBefore(day.atTime(request.goldenFrom()).toLocalTime())) {
                fit *= 1.2;
            }
            double stars = movie.getRatingCount() > 0 && movie.getRatingAverage() != null ? movie.getRatingAverage() : 3.0;
            double appeal = (tier == 1 ? 1.5 : tier == 2 ? 1.2 : 1.0) * (1 + (stars - 3) / 10);

            // Below its target share the movie is wanted (up to x2); above it, each extra show costs 40%.
            double goal = target.get(movie.getId());
            int count = shown.getOrDefault(movie.getId(), 0);
            double need = count < goal ? 1 + (goal - count) / goal : Math.pow(0.6, count - goal + 1);

            double spacing = 1;
            if (movie.getId().equals(neighbour)) {
                spacing *= 0.5; // same movie twice in a row in one room
            }
            boolean nearSameMovie = movieStarts.getOrDefault(movie.getId(), List.of()).stream()
                    .anyMatch(s -> Math.abs(ChronoUnit.MINUTES.between(s, start)) < SAME_MOVIE_GAP_MINUTES);
            if (nearSameMovie) {
                spacing *= 0.4;
            }
            if (crowded(start)) {
                spacing *= 0.85; // could not be staggered: another room starts within 10 minutes
            }
            return appeal * fit * need * spacing;
        }

        private Daypart daypart(LocalTime time) {
            if (time.isBefore(NOON)) {
                return Daypart.MORNING;
            }
            if (time.isBefore(request.goldenFrom())) {
                return Daypart.AFTERNOON;
            }
            return time.isBefore(request.goldenTo()) ? Daypart.PRIME : Daypart.LATE;
        }

        private boolean crowded(LocalDateTime start) {
            return allStarts.stream().anyMatch(s -> Math.abs(ChronoUnit.MINUTES.between(s, start)) < STAGGER_MINUTES);
        }

        /** Moves a start up to 30 minutes later so it is not within 10 minutes of another room's start. */
        private LocalDateTime staggerLater(LocalDateTime t) {
            for (int shift = 0; shift <= MAX_STAGGER_SHIFT; shift += ROUND_TO_MINUTES) {
                if (!crowded(t.plusMinutes(shift))) {
                    return t.plusMinutes(shift);
                }
            }
            return t;
        }

        private LocalDateTime staggerEarlier(LocalDateTime t) {
            for (int shift = 0; shift <= MAX_STAGGER_SHIFT; shift += ROUND_TO_MINUTES) {
                if (!crowded(t.minusMinutes(shift))) {
                    return t.minusMinutes(shift);
                }
            }
            return t;
        }

        private void place(Candidate c) {
            Room room = c.room;
            ScreenFormat format = room.getName().toUpperCase(Locale.ROOT).contains("3D") ? ScreenFormat.THREE_D : ScreenFormat.TWO_D;
            boolean golden = !c.start.toLocalTime().isBefore(request.goldenFrom()) && c.start.toLocalTime().isBefore(request.goldenTo());
            planned.add(new Planned(c.movie, room, c.start, c.movieEnd, c.roomFree, format, tierOf.get(c.movie.getId()), c.score, golden));
            record(c.movie.getId(), c.start);
        }

        private void record(Long movieId, LocalDateTime start) {
            shown.merge(movieId, 1, Integer::sum);
            movieStarts.computeIfAbsent(movieId, k -> new ArrayList<>()).add(start);
            allStarts.add(start);
        }
    }

    private enum Daypart { MORNING, AFTERNOON, PRIME, LATE }

    private record Candidate(Movie movie, Room room, LocalDateTime start, LocalDateTime movieEnd, LocalDateTime roomFree, double score) {
    }

    // ------------------------------------------------------------------ helpers

    static LocalDateTime roundUp(LocalDateTime t) {
        LocalDateTime base = t.withSecond(0).withNano(0);
        int extra = (ROUND_TO_MINUTES - base.getMinute() % ROUND_TO_MINUTES) % ROUND_TO_MINUTES;
        if (extra == 0 && (t.getSecond() > 0 || t.getNano() > 0)) {
            extra = ROUND_TO_MINUTES;
        }
        return base.plusMinutes(extra);
    }

    static LocalDateTime roundDown(LocalDateTime t) {
        LocalDateTime base = t.withSecond(0).withNano(0);
        return base.minusMinutes(base.getMinute() % ROUND_TO_MINUTES);
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
        if (r.trailerMinutes() < 0 || r.trailerMinutes() > 30) {
            throw new BusinessException("Thời gian quảng cáo trước phim từ 0 đến 30 phút");
        }
    }
}
