package com.bbcinema.dto.request;

import com.bbcinema.entity.enums.PostType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PostForm {

    private Long id;

    @NotBlank(message = "Vui lòng nhập tiêu đề")
    @Size(max = 200)
    private String title;

    @NotNull
    private PostType type = PostType.NEWS;

    @Size(max = 500)
    private String summary;

    @NotBlank(message = "Vui lòng nhập nội dung")
    private String content;

    private boolean published = true;

    private String thumbnailPath;
}
