package com.bbcinema.api;

import com.bbcinema.dto.request.ComboSelection;
import com.bbcinema.dto.request.HoldSeatsRequest;
import com.bbcinema.dto.response.BookingResponse;
import com.bbcinema.security.AppUserPrincipal;
import com.bbcinema.service.BookingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "Booking", description = "Đặt vé: giữ ghế, chọn combo, mã giảm giá, thanh toán, huỷ")
@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class BookingApiController {

    private final BookingService bookingService;

    @Operation(summary = "Giữ ghế, tạo đơn ở trạng thái chờ thanh toán")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse hold(@Valid @RequestBody HoldSeatsRequest request, @AuthenticationPrincipal AppUserPrincipal user) {
        return BookingResponse.from(bookingService.holdSeats(user.getId(), request.showtimeId(), request.showtimeSeatIds()));
    }

    @Operation(summary = "Lịch sử đặt vé của tôi")
    @GetMapping("/me")
    public Page<BookingResponse> mine(@AuthenticationPrincipal AppUserPrincipal user,
                                      @RequestParam(defaultValue = "0") int page) {
        return bookingService.history(user.getId(), PageRequest.of(Math.max(page, 0), 10)).map(BookingResponse::from);
    }

    @Operation(summary = "Chi tiết đơn đặt vé")
    @GetMapping("/{code}")
    public BookingResponse get(@PathVariable String code, @AuthenticationPrincipal AppUserPrincipal user) {
        return BookingResponse.from(bookingService.getForViewer(code, user.getId(), user.getRole()));
    }

    @Operation(summary = "Cập nhật combo bắp nước: { \"quantities\": { \"1\": 2 } }")
    @PutMapping("/{code}/combos")
    public BookingResponse combos(@PathVariable String code, @RequestBody ComboSelection selection,
                                  @AuthenticationPrincipal AppUserPrincipal user) {
        return BookingResponse.from(bookingService.updateCombos(code, user.getId(), selection.quantities()));
    }

    @Operation(summary = "Áp mã giảm giá: { \"code\": \"BBCINEMA10\" }")
    @PostMapping("/{code}/promotion")
    public BookingResponse promotion(@PathVariable String code, @RequestBody Map<String, String> body,
                                     @AuthenticationPrincipal AppUserPrincipal user) {
        return BookingResponse.from(bookingService.applyPromotion(code, user.getId(), body.get("code")));
    }

    @Operation(summary = "Dùng điểm thưởng: { \"points\": 20 }")
    @PostMapping("/{code}/points")
    public BookingResponse points(@PathVariable String code, @RequestBody Map<String, Integer> body,
                                  @AuthenticationPrincipal AppUserPrincipal user) {
        return BookingResponse.from(bookingService.usePoints(code, user.getId(), body.getOrDefault("points", 0)));
    }

    @Operation(summary = "Thanh toán thử (không qua cổng thanh toán)")
    @PostMapping("/{code}/pay")
    public BookingResponse pay(@PathVariable String code, @AuthenticationPrincipal AppUserPrincipal user) {
        return BookingResponse.from(bookingService.payWithMock(code, user.getId()));
    }

    @Operation(summary = "Huỷ đơn (vé đã thanh toán chỉ huỷ được trước giờ chiếu 2 tiếng)")
    @DeleteMapping("/{code}")
    public BookingResponse cancel(@PathVariable String code, @AuthenticationPrincipal AppUserPrincipal user) {
        return BookingResponse.from(bookingService.cancel(code, user.getId(), user.getRole()));
    }
}
