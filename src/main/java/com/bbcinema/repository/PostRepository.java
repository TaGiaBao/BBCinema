package com.bbcinema.repository;

import com.bbcinema.entity.Post;
import com.bbcinema.entity.enums.PostType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PostRepository extends JpaRepository<Post, Long> {

    Optional<Post> findBySlugAndPublishedTrue(String slug);

    boolean existsBySlug(String slug);

    Page<Post> findByPublishedTrueOrderByPublishedAtDesc(Pageable pageable);

    Page<Post> findByTypeAndPublishedTrueOrderByPublishedAtDesc(PostType type, Pageable pageable);

    List<Post> findTop4ByPublishedTrueOrderByPublishedAtDesc();

    List<Post> findAllByOrderByPublishedAtDesc();
}
