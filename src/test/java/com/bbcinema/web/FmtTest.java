package com.bbcinema.web;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class FmtTest {

    private final Fmt fmt = new Fmt();

    @Test
    void formatsMoneyWithDots() {
        assertThat(fmt.money(new BigDecimal("1234000"))).isEqualTo("1.234.000đ");
        assertThat(fmt.money(30)).isEqualTo("30đ");
        assertThat(fmt.money((Number) null)).isEqualTo("0đ");
    }

    @Test
    void formatsDuration() {
        assertThat(fmt.duration(135)).isEqualTo("2 giờ 15 phút");
        assertThat(fmt.duration(120)).isEqualTo("2 giờ");
        assertThat(fmt.duration(45)).isEqualTo("45 phút");
    }

    @Test
    void namesDays() {
        LocalDate friday = LocalDate.of(2026, 11, 20);
        assertThat(fmt.dayName(friday)).isEqualTo("Thứ Sáu");
        assertThat(fmt.relativeDay(friday, friday)).isEqualTo("Hôm nay");
        assertThat(fmt.relativeDay(friday, friday.minusDays(1))).isEqualTo("Ngày mai");
        assertThat(fmt.relativeDay(friday.plusDays(2), friday)).isEqualTo("Chủ Nhật");
    }

    @Test
    void buildsYoutubeEmbedLinks() {
        assertThat(fmt.youtubeEmbed("https://www.youtube.com/watch?v=abcDEF12345&t=10")).isEqualTo("https://www.youtube.com/embed/abcDEF12345");
        assertThat(fmt.youtubeEmbed("https://youtu.be/abcDEF12345")).isEqualTo("https://www.youtube.com/embed/abcDEF12345");
        assertThat(fmt.youtubeEmbed("https://vimeo.com/123")).isNull();
        assertThat(fmt.youtubeEmbed("https://www.youtube.com/watch?v=<script>")).isNull();
        assertThat(fmt.youtubeEmbed(null)).isNull();
    }

    @Test
    void safeRedirectOnlyAllowsLocalPaths() {
        assertThat(SafeRedirect.localPath("/booking/showtime/5", "/")).isEqualTo("/booking/showtime/5");
        assertThat(SafeRedirect.localPath("https://evil.example", "/")).isEqualTo("/");
        assertThat(SafeRedirect.localPath("//evil.example", "/")).isEqualTo("/");
        assertThat(SafeRedirect.localPath("/\\evil.example", "/")).isEqualTo("/");
        assertThat(SafeRedirect.localPath(null, "/home")).isEqualTo("/home");
    }
}
