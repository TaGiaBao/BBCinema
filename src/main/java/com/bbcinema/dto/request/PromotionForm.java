package com.bbcinema.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class PromotionForm {

    private Long id;

    @NotBlank(message = "Vui lòng nhập mã")
    @Pattern(regexp = "^[A-Za-z0-9_-]{3,30}$", message = "Mã gồm 3-30 ký tự chữ, số, gạch ngang")
    private String code;

    @Size(max = 255)
    private String description;

    @NotNull(message = "Vui lòng nhập phần trăm giảm")
    @Min(value = 1, message = "Giảm tối thiểu 1%")
    @Max(value = 100, message = "Giảm tối đa 100%")
    private Integer discountPercent;

    @DecimalMin(value = "0")
    private BigDecimal maxDiscount;

    @DecimalMin(value = "0")
    private BigDecimal minOrder;

    @NotNull(message = "Vui lòng chọn ngày bắt đầu")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate validFrom;

    @NotNull(message = "Vui lòng chọn ngày kết thúc")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate validTo;

    @Min(value = 1, message = "Số lượt tối thiểu là 1")
    private Integer usageLimit;

    private boolean active = true;
}
