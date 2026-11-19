package com.bbcinema.service;

import com.bbcinema.dto.response.DailyRevenue;
import com.bbcinema.dto.response.DashboardStats;
import com.bbcinema.dto.response.MovieRevenue;
import com.bbcinema.entity.Booking;
import com.bbcinema.entity.enums.MovieStatus;
import com.bbcinema.repository.BookingRepository;
import com.bbcinema.repository.MovieRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportService {

    private final BookingRepository bookingRepository;
    private final MovieRepository movieRepository;
    private final UserService userService;
    private final Clock clock;

    public DashboardStats dashboard() {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime dayStart = today.atStartOfDay();
        LocalDateTime dayEnd = today.plusDays(1).atStartOfDay();
        LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();
        return new DashboardStats(
                bookingRepository.sumRevenue(dayStart, dayEnd),
                bookingRepository.countPaid(dayStart, dayEnd),
                bookingRepository.sumRevenue(monthStart, dayEnd),
                bookingRepository.countPaid(monthStart, dayEnd),
                userService.countMembers(),
                movieRepository.findByStatusOrderByReleaseDateDesc(MovieStatus.NOW_SHOWING).size());
    }

    /** Revenue for every day in [from, to], including days without sales. */
    public List<DailyRevenue> dailyRevenue(LocalDate from, LocalDate to) {
        List<Booking> paid = bookingRepository.findPaidBetween(from.atStartOfDay(), to.plusDays(1).atStartOfDay());
        Map<LocalDate, List<Booking>> byDay = paid.stream().collect(Collectors.groupingBy(b -> b.getPaidAt().toLocalDate()));
        List<DailyRevenue> result = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            List<Booking> list = byDay.getOrDefault(d, List.of());
            result.add(new DailyRevenue(d, sum(list), list.size()));
        }
        return result;
    }

    /** Revenue per movie in [from, to], highest first. */
    public List<MovieRevenue> revenueByMovie(LocalDate from, LocalDate to) {
        List<Booking> paid = bookingRepository.findPaidBetween(from.atStartOfDay(), to.plusDays(1).atStartOfDay());
        Map<String, List<Booking>> byMovie = paid.stream()
                .collect(Collectors.groupingBy(b -> b.getShowtime().getMovie().getTitle()));
        return byMovie.entrySet().stream()
                .map(e -> new MovieRevenue(e.getKey(), sum(e.getValue()),
                        e.getValue().stream().mapToLong(b -> b.getSeats().size()).sum()))
                .sorted(Comparator.comparing(MovieRevenue::revenue).reversed())
                .toList();
    }

    private static BigDecimal sum(List<Booking> bookings) {
        return bookings.stream().map(Booking::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
