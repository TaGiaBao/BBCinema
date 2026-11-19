package com.bbcinema.web;

import com.bbcinema.entity.enums.PostType;
import com.bbcinema.service.ContentService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/news")
@RequiredArgsConstructor
public class NewsController {

    private final ContentService contentService;

    @GetMapping
    public String list(@RequestParam(required = false) PostType type,
                       @RequestParam(defaultValue = "0") int page,
                       Model model) {
        model.addAttribute("page", contentService.publishedPosts(type, PageRequest.of(Math.max(page, 0), 9)));
        model.addAttribute("type", type);
        return "news/list";
    }

    @GetMapping("/{slug}")
    public String detail(@PathVariable String slug, Model model) {
        model.addAttribute("post", contentService.getPublished(slug));
        model.addAttribute("latest", contentService.latestPosts());
        return "news/detail";
    }
}
