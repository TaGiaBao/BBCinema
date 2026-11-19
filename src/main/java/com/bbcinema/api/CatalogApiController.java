package com.bbcinema.api;

import com.bbcinema.dto.response.*;
import com.bbcinema.entity.TicketPrice;
import com.bbcinema.entity.enums.MovieStatus;
import com.bbcinema.service.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/** Public, read-only data: cinemas, movies, showtimes, seat maps, prices and combos. */
@Tag(name = "Catalog", description = "Dữ liệu công khai: rạp, phim, suất chiếu, giá vé")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CatalogApiController {

    private final CinemaService cinemaService;
    private final MovieService movieService;
    private final ShowtimeService showtimeService;
    private final PricingService pricingService;
    private final ComboService comboService;
    private final Clock clock;

    @Operation(summary = "Danh sách rạp đang hoạt động")
    @GetMapping("/cinemas")
    public List<CinemaResponse> cinemas() {
        return cinemaService.findActive().stream().map(CinemaResponse::from).toList();
    }

    @Operation(summary = "Lịch chiếu của rạp theo ngày, gom theo phim")
    @GetMapping("/cinemas/{id}/showtimes")
    public List<ShowtimeResponse> cinemaShowtimes(@PathVariable Long id,
                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate day = date == null ? LocalDate.now(clock) : date;
        return showtimeService.scheduleForCinema(id, day).stream()
                .flatMap(s -> s.showtimes().stream())
                .map(ShowtimeResponse::from)
                .toList();
    }

    @Operation(summary = "Tìm phim theo tên, trạng thái, thể loại (có phân trang)")
    @GetMapping("/movies")
    public Page<MovieResponse> movies(@RequestParam(required = false) String q,
                                      @RequestParam(required = false) MovieStatus status,
                                      @RequestParam(required = false) Long genreId,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "12") int size) {
        return movieService.search(q, status, genreId, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50)))
                .map(MovieResponse::from);
    }

    @Operation(summary = "Chi tiết phim")
    @GetMapping("/movies/{id}")
    public MovieResponse movie(@PathVariable Long id) {
        return MovieResponse.from(movieService.getById(id));
    }

    @Operation(summary = "Suất chiếu của phim tại một rạp trong một ngày (dùng cho popup Mua vé)")
    @GetMapping("/movies/{id}/showtimes")
    public List<ShowtimeResponse> movieShowtimes(@PathVariable Long id,
                                                 @RequestParam Long cinemaId,
                                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate day = date == null ? LocalDate.now(clock) : date;
        return showtimeService.findForMovie(id, cinemaId, day).stream().map(ShowtimeResponse::from).toList();
    }

    @Operation(summary = "Thông tin suất chiếu")
    @GetMapping("/showtimes/{id}")
    public ShowtimeResponse showtime(@PathVariable Long id) {
        return ShowtimeResponse.from(showtimeService.getDetailed(id));
    }

    @Operation(summary = "Sơ đồ ghế của suất chiếu kèm trạng thái và giá từng ghế")
    @GetMapping("/showtimes/{id}/seats")
    public SeatMap seats(@PathVariable Long id) {
        return showtimeService.seatMap(id);
    }

    public record PriceRow(String seatType, String format, String dayType, String timeSlot, BigDecimal price) {
        static PriceRow from(TicketPrice p) {
            return new PriceRow(p.getSeatType().name(), p.getFormat().name(), p.getDayType().name(), p.getTimeSlot().name(), p.getPrice());
        }
    }

    @Operation(summary = "Bảng giá vé")
    @GetMapping("/ticket-prices")
    public List<PriceRow> prices() {
        return pricingService.findAll().stream().map(PriceRow::from).toList();
    }

    public record ComboRow(Long id, String name, String description, BigDecimal price, String imageUrl) {
    }

    @Operation(summary = "Combo bắp nước đang bán")
    @GetMapping("/combos")
    public List<ComboRow> combos() {
        return comboService.findActive().stream()
                .map(c -> new ComboRow(c.getId(), c.getName(), c.getDescription(), c.getPrice(), c.getImagePath()))
                .toList();
    }
}
