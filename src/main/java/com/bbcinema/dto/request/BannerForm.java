package com.bbcinema.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class BannerForm {

    private Long id;

    @NotBlank(message = "Vui lòng nhập tiêu đề")
    @Size(max = 150)
    private String title;

    @Size(max = 255)
    private String subtitle;

    @Size(max = 150)
    private String titleEn;

    @Size(max = 255)
    private String subtitleEn;

    private boolean captionHidden;

    @Size(max = 255)
    private String link;

    private int sortOrder;

    private boolean active = true;

    private String imagePath;
}
