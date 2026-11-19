package com.bbcinema.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

@Data
public class RegisterForm {

    @NotBlank(message = "Vui lòng nhập họ tên")
    @Size(max = 100, message = "Họ tên tối đa 100 ký tự")
    private String fullName;

    @NotBlank(message = "Vui lòng nhập email")
    @Email(message = "Email không hợp lệ")
    @Size(max = 120)
    private String email;

    @Pattern(regexp = "^$|^0\\d{9,10}$", message = "Số điện thoại gồm 10-11 số, bắt đầu bằng 0")
    private String phone;

    @NotNull(message = "Vui lòng nhập ngày sinh")
    @Past(message = "Ngày sinh phải ở trong quá khứ")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate birthDate;

    @NotBlank(message = "Vui lòng nhập mật khẩu")
    @Size(min = 6, max = 64, message = "Mật khẩu từ 6 đến 64 ký tự")
    private String password;

    @NotBlank(message = "Vui lòng nhập lại mật khẩu")
    private String confirmPassword;
}
