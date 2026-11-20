package com.bbcinema.dto.request;

import com.bbcinema.entity.enums.DiscountType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/** The birthday gift settings form on /admin/promotions/birthday. Cross-field rules are checked by the service. */
@Data
public class BirthdayRewardForm {

    private boolean enabled;

    @NotNull(message = "Vui lòng chọn cách giảm giá")
    private DiscountType discountType = DiscountType.PERCENT;

    @NotNull(message = "Vui lòng nhập mức giảm")
    @DecimalMin(value = "1", message = "Mức giảm phải lớn hơn 0")
    @Digits(integer = 12, fraction = 0, message = "Mức giảm phải là số nguyên")
    private BigDecimal discountValue;

    @DecimalMin(value = "0", message = "Giảm tối đa không được âm")
    @Digits(integer = 12, fraction = 0, message = "Giảm tối đa phải là số nguyên")
    private BigDecimal maxDiscount;

    @DecimalMin(value = "0", message = "Đơn tối thiểu không được âm")
    @Digits(integer = 12, fraction = 0, message = "Đơn tối thiểu phải là số nguyên")
    private BigDecimal minOrder;
}
