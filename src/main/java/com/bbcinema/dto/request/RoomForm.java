package com.bbcinema.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class RoomForm {

    /** Every room has at least rows A-I: A-E standard and F-I VIP. */
    public static final int MIN_ROWS = 9;

    @NotNull(message = "Vui lòng chọn rạp")
    private Long cinemaId;

    @NotBlank(message = "Vui lòng nhập tên phòng")
    @Size(max = 50)
    private String name;

    @NotNull
    @Min(value = MIN_ROWS, message = "Phòng chiếu cần ít nhất 9 hàng (A-I)")
    @Max(value = 26, message = "Tối đa 26 hàng (A-Z)")
    private Integer rowCount = 10;

    @NotNull
    @Min(value = 1, message = "Ít nhất 1 ghế mỗi hàng")
    @Max(value = 30, message = "Tối đa 30 ghế mỗi hàng")
    private Integer seatsPerRow = 12;

    /** Comma separated row letters for VIP seats. Rows F-I are VIP in the standard layout. */
    @Pattern(regexp = "^$|^[A-Za-z](\\s*,\\s*[A-Za-z])*$", message = "Nhập các chữ cái hàng, cách nhau bởi dấu phẩy. Ví dụ: F,G,H,I")
    private String vipRows = "F,G,H,I";

    /** Row letter for couple seats; only the last row may hold them. */
    @Pattern(regexp = "^$|^[A-Za-z](\\s*,\\s*[A-Za-z])*$", message = "Nhập chữ cái của hàng cuối. Ví dụ: J")
    private String coupleRows = "J";
}
