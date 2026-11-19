package com.bbcinema.entity;

import com.bbcinema.entity.enums.PostType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "posts")
@Getter
@Setter
@NoArgsConstructor
public class Post {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, unique = true, length = 220)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PostType type = PostType.NEWS;

    @Column(length = 500)
    private String summary;

    /** Long text: length 20,000 makes Hibernate use a TEXT column on MySQL. */
    @Lob
    @Column(nullable = false, length = 20000)
    private String content;

    @Column(name = "thumbnail_path")
    private String thumbnailPath;

    @Column(nullable = false)
    private boolean published = true;

    @Column(name = "published_at", nullable = false)
    private LocalDateTime publishedAt;
}
