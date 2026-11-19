package com.bbcinema.dto.request;

import com.bbcinema.entity.enums.AgeRating;
import com.bbcinema.entity.enums.MovieStatus;
import jakarta.validation.constraints.*;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Data
public class MovieForm {

    private Long id;

    @NotBlank(message = "Vui lòng nhập tên phim")
    @Size(max = 150)
    private String title;

    @Size(max = 120)
    private String director;

    @Size(max = 255)
    private String actors;

    @Size(max = 2000, message = "Mô tả tối đa 2000 ký tự")
    private String description;

    @NotNull(message = "Vui lòng nhập thời lượng")
    @Min(value = 30, message = "Thời lượng tối thiểu 30 phút")
    @Max(value = 300, message = "Thời lượng tối đa 300 phút")
    private Integer durationMinutes;

    @NotNull
    private AgeRating ageRating = AgeRating.P;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate releaseDate;

    @Size(max = 60)
    private String language;

    @Pattern(regexp = "^$|^https?://.+", message = "Link trailer phải bắt đầu bằng http:// hoặc https://")
    private String trailerUrl;

    @NotNull
    private MovieStatus status = MovieStatus.COMING_SOON;

    private List<Long> genreIds = new ArrayList<>();

    /** Current poster, shown in the edit form. */
    private String posterPath;
}
