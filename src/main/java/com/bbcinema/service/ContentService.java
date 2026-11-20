package com.bbcinema.service;

import com.bbcinema.dto.request.BannerForm;
import com.bbcinema.dto.request.PostForm;
import com.bbcinema.entity.Banner;
import com.bbcinema.entity.Post;
import com.bbcinema.entity.enums.PostType;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.exception.NotFoundException;
import com.bbcinema.repository.BannerRepository;
import com.bbcinema.repository.PostRepository;
import com.bbcinema.util.SlugUtil;
import com.bbcinema.util.TextUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/** News, promotion articles and home page banners. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ContentService {

    private final PostRepository postRepository;
    private final BannerRepository bannerRepository;
    private final FileStorageService fileStorageService;
    private final Clock clock;

    // ----- Posts -----

    public Page<Post> publishedPosts(PostType type, Pageable pageable) {
        return type == null
                ? postRepository.findByPublishedTrueOrderByPublishedAtDesc(pageable)
                : postRepository.findByTypeAndPublishedTrueOrderByPublishedAtDesc(type, pageable);
    }

    public List<Post> latestPosts() {
        return postRepository.findTop4ByPublishedTrueOrderByPublishedAtDesc();
    }

    public Post getPublished(String slug) {
        return postRepository.findBySlugAndPublishedTrue(slug).orElseThrow(() -> new NotFoundException("Không tìm thấy bài viết"));
    }

    public List<Post> allPosts() {
        return postRepository.findAllByOrderByPublishedAtDesc();
    }

    public Post getPost(Long id) {
        return postRepository.findById(id).orElseThrow(() -> new NotFoundException("Không tìm thấy bài viết"));
    }

    @Transactional
    public Post savePost(PostForm form, MultipartFile thumbnail) {
        Post post = form.getId() == null ? new Post() : getPost(form.getId());
        if (post.getId() == null) {
            post.setPublishedAt(LocalDateTime.now(clock));
            post.setSlug(uniquePostSlug(form.getTitle()));
        }
        post.setTitle(form.getTitle().trim());
        post.setType(form.getType());
        post.setSummary(TextUtil.blankToNull(form.getSummary()));
        post.setContent(form.getContent());
        post.setPublished(form.isPublished());
        String path = fileStorageService.storeImage(thumbnail, "posts");
        if (path != null) {
            post.setThumbnailPath(path);
        }
        return postRepository.save(post);
    }

    @Transactional
    public void deletePost(Long id) {
        postRepository.delete(getPost(id));
    }

    public PostForm toForm(Post post) {
        PostForm form = new PostForm();
        form.setId(post.getId());
        form.setTitle(post.getTitle());
        form.setType(post.getType());
        form.setSummary(post.getSummary());
        form.setContent(post.getContent());
        form.setPublished(post.isPublished());
        form.setThumbnailPath(post.getThumbnailPath());
        return form;
    }

    private String uniquePostSlug(String title) {
        String base = SlugUtil.toSlug(title);
        if (base.isEmpty()) {
            base = "bai-viet";
        }
        String slug = base;
        int counter = 2;
        while (postRepository.existsBySlug(slug)) {
            slug = base + "-" + counter++;
        }
        return slug;
    }

    // ----- Banners -----

    public List<Banner> activeBanners() {
        return bannerRepository.findByActiveTrueOrderBySortOrderAsc();
    }

    public List<Banner> allBanners() {
        return bannerRepository.findAllByOrderBySortOrderAsc();
    }

    public Banner getBanner(Long id) {
        return bannerRepository.findById(id).orElseThrow(() -> new NotFoundException("Không tìm thấy banner"));
    }

    @Transactional
    public Banner saveBanner(BannerForm form, MultipartFile image) {
        Banner banner = form.getId() == null ? new Banner() : getBanner(form.getId());
        String path = fileStorageService.storeImage(image, "banners");
        if (path != null) {
            banner.setImagePath(path);
        }
        if (banner.getImagePath() == null) {
            throw new BusinessException("Vui lòng chọn ảnh banner");
        }
        banner.setTitle(form.getTitle().trim());
        banner.setSubtitle(TextUtil.blankToNull(form.getSubtitle()));
        banner.setTitleEn(TextUtil.blankToNull(form.getTitleEn()));
        banner.setSubtitleEn(TextUtil.blankToNull(form.getSubtitleEn()));
        banner.setCaptionHidden(form.isCaptionHidden());
        banner.setLink(TextUtil.blankToNull(form.getLink()));
        banner.setSortOrder(form.getSortOrder());
        banner.setActive(form.isActive());
        return bannerRepository.save(banner);
    }

    @Transactional
    public void deleteBanner(Long id) {
        bannerRepository.delete(getBanner(id));
    }

    public BannerForm toForm(Banner banner) {
        BannerForm form = new BannerForm();
        form.setId(banner.getId());
        form.setTitle(banner.getTitle());
        form.setSubtitle(banner.getSubtitle());
        form.setTitleEn(banner.getTitleEn());
        form.setSubtitleEn(banner.getSubtitleEn());
        form.setCaptionHidden(banner.isCaptionHidden());
        form.setLink(banner.getLink());
        form.setSortOrder(banner.getSortOrder());
        form.setActive(banner.isActive());
        form.setImagePath(banner.getImagePath());
        return form;
    }
}
