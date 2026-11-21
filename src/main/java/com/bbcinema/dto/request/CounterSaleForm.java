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

    /** Ticket type per seat (showtime seat id), from the "audience_{id}" selects; seats left out are adult tickets. */
    private Map<Long, com.bbcinema.entity.enums.AudienceType> seatAudiences = new HashMap<>();

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
     * Member code of the member staff looked up (by code or phone), or blank for a walk-in customer. The server
     * resolves the code again with the lookup's rules and works out the discount itself.
     */
    @Size(max = 20)
    private String memberCode;

    /**
     * Staff confirmed the member: always set for a code match, ticked by staff for a phone match after the
     * customer confirmed their full name. Required whenever a member is attached.
     */
    private boolean memberConfirmed;

    /** CODE or PHONE: how the member was found. Only used to show the panel again after a failed sale. */
    @Size(max = 10)
    private String memberMatchedBy;

    /** Printed on the POS terminal's slip when the customer pays by card. */
    @Size(max = 12)
    private String cardApprovalCode;
}
