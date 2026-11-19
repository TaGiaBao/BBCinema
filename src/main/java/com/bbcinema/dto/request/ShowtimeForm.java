package com.bbcinema.dto.request;

import com.bbcinema.entity.enums.ScreenFormat;
import com.bbcinema.entity.enums.ShowLanguage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/** Creates one showtime per (date, time) pair: every date from {@code fromDate} to {@code toDate} at each listed time. */
@Data
public class ShowtimeForm {

    @NotNull(message = "Vui lòng chọn phim")
    private Long movieId;

    @NotNull(message = "Vui lòng chọn phòng")
    private Long roomId;

    @NotNull(message = "Vui lòng chọn ngày bắt đầu")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate fromDate;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate toDate;

    /** Times separated by commas, e.g. "09:30, 13:00, 19:45". */
    @NotBlank(message = "Vui lòng nhập giờ chiếu")
    private String times;

    @NotNull
    private ScreenFormat format = ScreenFormat.TWO_D;

    /** Null means: use the default for the movie (Vietnamese films in Vietnamese, others subtitled). */
    private ShowLanguage language;

    private boolean special;
}
