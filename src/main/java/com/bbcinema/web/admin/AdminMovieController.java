package com.bbcinema.web.admin;

import com.bbcinema.dto.request.MovieForm;
import com.bbcinema.entity.enums.AgeRating;
import com.bbcinema.entity.enums.MovieStatus;
import com.bbcinema.exception.BusinessException;
import com.bbcinema.service.MovieService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/movies")
@RequiredArgsConstructor
public class AdminMovieController {

    private final MovieService movieService;

    @ModelAttribute("menu")
    public String menu() {
        return "movies";
    }

    @GetMapping
    public String list(@RequestParam(required = false) String q,
                       @RequestParam(required = false) MovieStatus status,
                       @RequestParam(defaultValue = "0") int page,
                       Model model) {
        model.addAttribute("page", movieService.search(q, status, null, PageRequest.of(Math.max(page, 0), 15)));
        model.addAttribute("q", q);
        model.addAttribute("status", status);
        model.addAttribute("statuses", MovieStatus.values());
        model.addAttribute("genres", movieService.findAllGenres());
        return "admin/movies/list";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        return form(model, new MovieForm());
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, Model model) {
        return form(model, movieService.toForm(movieService.getById(id)));
    }

    @PostMapping("/save")
    public String save(@Valid @ModelAttribute("form") MovieForm form,
                       BindingResult binding,
                       @RequestParam(name = "poster", required = false) MultipartFile poster,
                       Model model,
                       RedirectAttributes redirect) {
        if (binding.hasErrors()) {
            return form(model, form);
        }
        try {
            movieService.save(form, poster);
        } catch (BusinessException e) {
            binding.reject("save", e.getMessage());
            return form(model, form);
        }
        redirect.addFlashAttribute("success", "Đã lưu phim \"" + form.getTitle() + "\"");
        return "redirect:/admin/movies";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            movieService.delete(id);
            redirect.addFlashAttribute("success", "Đã xoá phim");
        } catch (BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/movies";
    }

    @PostMapping("/genres")
    public String addGenre(@RequestParam String name, RedirectAttributes redirect) {
        try {
            movieService.createGenre(name);
            redirect.addFlashAttribute("success", "Đã thêm thể loại " + name.trim());
        } catch (BusinessException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/movies";
    }

    @PostMapping("/genres/{id}/delete")
    public String deleteGenre(@PathVariable Long id, RedirectAttributes redirect) {
        movieService.deleteGenre(id);
        redirect.addFlashAttribute("success", "Đã xoá thể loại");
        return "redirect:/admin/movies";
    }

    private String form(Model model, MovieForm form) {
        model.addAttribute("form", form);
        model.addAttribute("genres", movieService.findAllGenres());
        model.addAttribute("ratings", AgeRating.values());
        model.addAttribute("statuses", MovieStatus.values());
        return "admin/movies/form";
    }
}
