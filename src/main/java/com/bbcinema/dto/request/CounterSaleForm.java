package com.bbcinema.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class CounterSaleForm {

    private List<Long> showtimeSeatIds = new ArrayList<>();

    private Map<Long, Integer> comboQuantities = new HashMap<>();

    @Size(max = 100)
    private String customerName;

    @Pattern(regexp = "^$|^0\\d{9,10}$", message = "Số điện thoại gồm 10-11 số, bắt đầu bằng 0")
    private String customerPhone;

    @Email(message = "Email không hợp lệ")
    private String customerEmail;

    private com.bbcinema.entity.enums.PaymentMethod paymentMethod = com.bbcinema.entity.enums.PaymentMethod.CASH;

    /** Cash: what the customer hands over. Card: the amount printed on the POS slip. */
    @DecimalMin(value = "0", message = "Số tiền không hợp lệ")
    @Digits(integer = 12, fraction = 0, message = "Số tiền không hợp lệ")
    private BigDecimal amountReceived;

    /**
     * The member staff looked up (member code or phone), or null for a walk-in customer. Only an id: the server
     * checks the account again and works out the discount itself.
     */
    private Long memberId;

    /** Printed on the POS terminal's slip when the customer pays by card. */
    @Size(max = 12)
    private String cardApprovalCode;
}
