package com.bbcinema.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

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
}
