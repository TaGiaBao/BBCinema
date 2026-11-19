package com.bbcinema.dto.request;

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
}
