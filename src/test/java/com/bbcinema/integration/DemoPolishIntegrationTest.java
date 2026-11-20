package com.bbcinema.integration;

import com.bbcinema.entity.Banner;
import com.bbcinema.entity.enums.AgeRating;
import com.bbcinema.entity.enums.PaymentStatus;
import com.bbcinema.repository.BannerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Fixes made before the defence: banner captions, poster badges, labels, demo-only pages. */
class DemoPolishIntegrationTest extends IntegrationTestBase {

    @Autowired BannerRepository bannerRepository;

    @Test
    void bannerTextIsWrittenOverThePictureInTheVisitorsLanguage() throws Exception {
        Banner banner = new Banner();
        banner.setTitle("Tiêu đề banner thử " + unique());
        banner.setSubtitle("Mô tả tiếng Việt");
        banner.setTitleEn("English banner title");
        banner.setImagePath("/images/banners/banner-1.svg");
        banner.setSortOrder(-100);
        bannerRepository.save(banner);

        mvc.perform(get("/"))
                .andExpect(content().string(containsString(banner.getTitle())))
                .andExpect(content().string(containsString("Mô tả tiếng Việt")))
                .andExpect(content().string(containsString("Đặt vé ngay")));
        mvc.perform(get("/").param("lang", "en"))
                .andExpect(content().string(containsString("English banner title")))
                .andExpect(content().string(containsString("Mô tả tiếng Việt"))) // no English subtitle: Vietnamese one
                .andExpect(content().string(containsString("Book now")));

        banner.setCaptionHidden(true); // the picture already has its own text
        bannerRepository.save(banner);
        mvc.perform(get("/")).andExpect(content().string(not(containsString("Mô tả tiếng Việt"))));
        bannerRepository.delete(banner);
    }

    @Test
    void moviePosterShowsTheAgeBadge() throws Exception {
        showtime(AgeRating.T16, LocalDateTime.now().plusDays(1).withHour(20).withMinute(0).withSecond(0).withNano(0));
        mvc.perform(get("/movies"))
                .andExpect(content().string(containsString("age-badge poster-age T16")));
    }

    @Test
    void demoMailboxDoesNotExistWhenTurnedOff() throws Exception {
        mvc.perform(get("/demo/mailbox")).andExpect(status().isNotFound());
        mvc.perform(get("/demo/mailbox/1")).andExpect(status().isNotFound());
    }

    @Test
    void paymentStatusHasAReadableLabel() {
        assertThat(PaymentStatus.SUCCESS.getLabel()).isEqualTo("Thành công");
        assertThat(PaymentStatus.REFUNDED.getLabel()).isEqualTo("Đã hoàn tiền");
    }
}
