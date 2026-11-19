package com.bbcinema.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ComboForm {

    private Long id;

    @NotBlank(message = "Vui lòng nhập tên combo")
    @Size(max = 100)
    private String name;

    @Size(max = 255)
    private String description;

    @NotNull(message = "Vui lòng nhập giá")
    @DecimalMin(value = "0", message = "Giá không được âm")
    private BigDecimal price;

    private boolean active = true;

    private String imagePath;
}
