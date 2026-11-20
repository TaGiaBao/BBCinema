package com.bbcinema.service;

import com.bbcinema.entity.Holiday;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.exception.NotFoundException;
import com.bbcinema.repository.HolidayRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Public holidays. Shows starting on a holiday are sold at the holiday prices. Since a showtime's
 * prices are fixed into its seats when it is created, adding or removing a holiday re-prices the
 * free seats of the showtimes already scheduled on that date.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HolidayService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final HolidayRepository holidayRepository;
    private final PricingService pricingService;
    private final Clock clock;

    /** The result of adding or removing a holiday: the holiday and how many free seats got a new price. */
    public record Change(Holiday holiday, int repricedSeats) {
    }

    @Transactional(readOnly = true)
    public List<Holiday> findAll() {
        return holidayRepository.findAllByOrderByDateAsc();
    }

    /** Holidays from today to {@code months} months ahead. */
    @Transactional(readOnly = true)
    public List<Holiday> upcoming(int months) {
        LocalDate today = LocalDate.now(clock);
        return holidayRepository.findByDateBetweenOrderByDateAsc(today, today.plusMonths(months));
    }

    /** Holiday dates among {@code dates}, for marking them on a date strip. */
    @Transactional(readOnly = true)
    public Set<LocalDate> holidayDatesIn(List<LocalDate> dates) {
        if (dates.isEmpty()) {
            return Set.of();
        }
        LocalDate from = dates.stream().min(LocalDate::compareTo).orElseThrow();
        LocalDate to = dates.stream().max(LocalDate::compareTo).orElseThrow();
        Set<LocalDate> result = new HashSet<>();
        for (Holiday h : holidayRepository.findByDateBetweenOrderByDateAsc(from, to)) {
            result.add(h.getDate());
        }
        return result;
    }

    @Transactional
    public Change add(LocalDate date, String name) {
        if (date == null) {
            throw new BusinessException("Vui lòng chọn ngày");
        }
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isEmpty()) {
            throw new BusinessException("Vui lòng nhập tên ngày lễ");
        }
        if (cleanName.length() > 100) {
            throw new BusinessException("Tên ngày lễ tối đa 100 ký tự");
        }
        holidayRepository.findByDate(date).ifPresent(existing -> {
            throw new BusinessException("Ngày " + DATE.format(date) + " đã có trong danh sách ngày lễ (" + existing.getName() + ")");
        });
        Holiday holiday = holidayRepository.saveAndFlush(new Holiday(date, cleanName));
        int repriced = pricingService.repriceAvailableSeatsOn(List.of(date));
        log.info("Holiday {} {} added, {} free seats re-priced", date, cleanName, repriced);
        return new Change(holiday, repriced);
    }

    @Transactional
    public Change delete(Long id) {
        Holiday holiday = holidayRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy ngày lễ #" + id));
        holidayRepository.delete(holiday);
        holidayRepository.flush();
        int repriced = pricingService.repriceAvailableSeatsOn(List.of(holiday.getDate()));
        log.info("Holiday {} {} removed, {} free seats re-priced", holiday.getDate(), holiday.getName(), repriced);
        return new Change(holiday, repriced);
    }
}
