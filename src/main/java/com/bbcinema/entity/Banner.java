package com.bbcinema.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "banners")
@Getter
@Setter
@NoArgsConstructor
public class Banner {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String title;

    /** Line under the title on the home page. */
    @Column(length = 255)
    private String subtitle;

    /** English title and subtitle; the Vietnamese ones are used when these are empty. */
    @Column(name = "title_en", length = 150)
    private String titleEn;

    @Column(name = "subtitle_en", length = 255)
    private String subtitleEn;

    /** True when the picture already has its own text, so the home page writes nothing over it. */
    @Column(name = "caption_hidden", nullable = false)
    private boolean captionHidden = false;

    @Column(name = "image_path", nullable = false)
    private String imagePath;

    @Column(length = 255)
    private String link;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(nullable = false)
    private boolean active = true;

    public String titleFor(String lang) {
        return "en".equals(lang) && titleEn != null ? titleEn : title;
    }

    public String subtitleFor(String lang) {
        return "en".equals(lang) && subtitleEn != null ? subtitleEn : subtitle;
    }
}
