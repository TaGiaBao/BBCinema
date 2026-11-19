package com.bbcinema.web.admin;

import com.bbcinema.dto.request.BannerForm;
import com.bbcinema.dto.request.PostForm;
import com.bbcinema.entity.enums.PostType;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.service.ContentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** News / promotion articles and home page banners. */
@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminContentController {

    private final ContentService contentService;

    // ----- posts

    @GetMapping("/posts")
    public String posts(Model model) {
        model.addAttribute("posts", contentService.allPosts());
        model.addAttribute("menu", "posts");
        return "admin/posts/list";
    }

    @GetMapping("/posts/new")
    public String newPost(Model model) {
        return postForm(model, new PostForm());
    }

    @GetMapping("/posts/{id}/edit")
    public String editPost(@PathVariable Long id, Model model) {
        return postForm(model, contentService.toForm(contentService.getPost(id)));
    }

    @PostMapping("/posts")
    public String savePost(@Valid @ModelAttribute("form") PostForm form, BindingResult binding,
                           @RequestParam(name = "thumbnail", required = false) MultipartFile thumbnail,
                           Model model, RedirectAttributes redirect) {
        if (binding.hasErrors()) {
            return postForm(model, form);
        }
        try {
            contentService.savePost(form, thumbnail);
        } catch (BusinessException e) {
            binding.reject("post", e.getMessage());
            return postForm(model, form);
        }
        redirect.addFlashAttribute("success", "Đã lưu bài viết");
        return "redirect:/admin/posts";
    }

    @PostMapping("/posts/{id}/delete")
    public String deletePost(@PathVariable Long id, RedirectAttributes redirect) {
        contentService.deletePost(id);
        redirect.addFlashAttribute("success", "Đã xoá bài viết");
        return "redirect:/admin/posts";
    }

    private String postForm(Model model, PostForm form) {
        model.addAttribute("form", form);
        model.addAttribute("types", PostType.values());
        model.addAttribute("menu", "posts");
        return "admin/posts/form";
    }

    // ----- banners

    @GetMapping("/banners")
    public String banners(Model model) {
        model.addAttribute("banners", contentService.allBanners());
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new BannerForm());
        }
        model.addAttribute("menu", "banners");
        return "admin/banners";
    }

    @GetMapping("/banners/{id}/edit")
    public String editBanner(@PathVariable Long id, Model model) {
        model.addAttribute("form", contentService.toForm(contentService.getBanner(id)));
        return banners(model);
    }

    @PostMapping("/banners")
    public String saveBanner(@Valid @ModelAttribute("form") BannerForm form, BindingResult binding,
                             @RequestParam(name = "image", required = false) MultipartFile image,
                             Model model, RedirectAttributes redirect) {
        if (binding.hasErrors()) {
            return banners(model);
        }
        try {
            contentService.saveBanner(form, image);
        } catch (BusinessException e) {
            binding.reject("banner", e.getMessage());
            return banners(model);
        }
        redirect.addFlashAttribute("success", "Đã lưu banner");
        return "redirect:/admin/banners";
    }

    @PostMapping("/banners/{id}/delete")
    public String deleteBanner(@PathVariable Long id, RedirectAttributes redirect) {
        contentService.deleteBanner(id);
        redirect.addFlashAttribute("success", "Đã xoá banner");
        return "redirect:/admin/banners";
    }
}
