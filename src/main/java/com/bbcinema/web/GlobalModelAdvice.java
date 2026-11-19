package com.bbcinema.web;

import com.bbcinema.entity.Cinema;
import com.bbcinema.security.AppUserPrincipal;
import com.bbcinema.service.CinemaService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;

/** Adds data that the header of every page needs: the cinema list and the chosen cinema. */
@ControllerAdvice(basePackages = "com.bbcinema.web")
@RequiredArgsConstructor
public class GlobalModelAdvice {

    public static final String CINEMA_COOKIE = "BB_CINEMA";

    private final CinemaService cinemaService;

    @ModelAttribute("cinemas")
    public List<Cinema> cinemas() {
        return cinemaService.findActive();
    }

    @ModelAttribute("currentCinema")
    public Cinema currentCinema(@CookieValue(name = CINEMA_COOKIE, required = false) Long cinemaId) {
        return cinemaService.resolveSelected(cinemaId).orElse(null);
    }

    @ModelAttribute("currentUser")
    public AppUserPrincipal currentUser(@AuthenticationPrincipal AppUserPrincipal principal) {
        return principal;
    }

    @ModelAttribute("currentPath")
    public String currentPath(HttpServletRequest request) {
        return request.getRequestURI();
    }
}
