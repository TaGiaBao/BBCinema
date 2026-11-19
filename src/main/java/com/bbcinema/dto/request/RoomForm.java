package com.bbcinema.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class RoomForm {

    @NotNull(message = "Vui lòng chọn rạp")
    private Long cinemaId;

    @NotBlank(message = "Vui lòng nhập tên phòng")
    @Size(max = 50)
    private String name;

    @NotNull
    @Min(value = 1, message = "Ít nhất 1 hàng")
    @Max(value = 26, message = "Tối đa 26 hàng (A-Z)")
    private Integer rowCount = 8;

    @NotNull
    @Min(value = 1, message = "Ít nhất 1 ghế mỗi hàng")
    @Max(value = 30, message = "Tối đa 30 ghế mỗi hàng")
    private Integer seatsPerRow = 12;

    /** Comma separated row letters for VIP seats, e.g. "E,F,G". */
    @Pattern(regexp = "^$|^[A-Za-z](\\s*,\\s*[A-Za-z])*$", message = "Nhập các chữ cái hàng, cách nhau bởi dấu phẩy. Ví dụ: E,F")
    private String vipRows = "";

    @Pattern(regexp = "^$|^[A-Za-z](\\s*,\\s*[A-Za-z])*$", message = "Nhập các chữ cái hàng, cách nhau bởi dấu phẩy. Ví dụ: H")
    private String coupleRows = "";
}
