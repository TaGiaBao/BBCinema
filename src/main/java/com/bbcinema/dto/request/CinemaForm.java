package com.bbcinema.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CinemaForm {

    private Long id;

    @NotBlank(message = "Vui lòng nhập tên rạp")
    @Size(max = 100)
    private String name;

    @NotBlank(message = "Vui lòng nhập địa chỉ")
    @Size(max = 255)
    private String address;

    @Size(max = 20)
    private String phone;

    private boolean active = true;

    /** Decimal degrees, e.g. 21.0365 (Hà Nội). Both or neither must be given. */
    @DecimalMin(value = "-90", message = "Vĩ độ từ -90 đến 90")
    @DecimalMax(value = "90", message = "Vĩ độ từ -90 đến 90")
    private Double latitude;

    @DecimalMin(value = "-180", message = "Kinh độ từ -180 đến 180")
    @DecimalMax(value = "180", message = "Kinh độ từ -180 đến 180")
    private Double longitude;
}
