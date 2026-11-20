package com.bbcinema.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

@Data
public class HolidayForm {

    @NotNull(message = "Vui lòng chọn ngày")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate date;

    @NotBlank(message = "Vui lòng nhập tên ngày lễ")
    @Size(max = 100, message = "Tên ngày lễ tối đa 100 ký tự")
    private String name;
}
