package com.bbcinema.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class SlugUtilTest {

    @ParameterizedTest
    @CsvSource({
            "Hành Tinh Lặng Im, hanh-tinh-lang-im",
            "Đỉnh Gió Bắc, dinh-gio-bac",
            "  Căn Nhà Số 13  , can-nha-so-13",
            "Thứ Tư vui vẻ: đồng giá 50.000đ, thu-tu-vui-ve-dong-gia-50-000d",
            "---Hello   World!!!---, hello-world"
    })
    void convertsVietnameseTitles(String input, String expected) {
        assertThat(SlugUtil.toSlug(input)).isEqualTo(expected);
    }

    @Test
    void nullBecomesEmpty() {
        assertThat(SlugUtil.toSlug(null)).isEmpty();
    }
}
