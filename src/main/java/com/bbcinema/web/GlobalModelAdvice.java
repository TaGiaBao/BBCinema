package com.bbcinema.web;

import com.bbcinema.entity.Cinema;
import com.bbcinema.security.AppUserPrincipal;
import com.bbcinema.service.CinemaService;
import com.bbcinema.service.ShowtimeService;
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
    private final com.bbcinema.service.ShiftService shiftService;

    @ModelAttribute("cinemas")
    public List<Cinema> cinemas() {
        return cinemaService.findActive();
    }

    @ModelAttribute("currentCinema")
    public Cinema currentCinema(@CookieValue(name = CINEMA_COOKIE, required = false) Long cinemaId) {
        return cinemaService.resolveSelected(cinemaId).orElse(null);
    }

    /**
     * False until the visitor has picked a cinema (the cookie is missing or points to a closed
     * cinema); the customer layout then asks where they want to watch.
     */
    @ModelAttribute("cinemaChosen")
    public boolean cinemaChosen(@CookieValue(name = CINEMA_COOKIE, required = false) Long cinemaId) {
        return cinemaId != null && cinemaService.findActive().stream().anyMatch(c -> c.getId().equals(cinemaId));
    }

    @ModelAttribute("currentUser")
    public AppUserPrincipal currentUser(@AuthenticationPrincipal AppUserPrincipal principal) {
        return principal;
    }

    /** The till shift a counter staff member is working in, or null (always null for other roles). */
    @ModelAttribute("openShift")
    public com.bbcinema.entity.CounterShift openShift(@AuthenticationPrincipal AppUserPrincipal principal) {
        if (principal == null || principal.getRole() != com.bbcinema.entity.enums.Role.STAFF) {
            return null;
        }
        return shiftService.findOpen(principal.getId()).orElse(null);
    }

    /** Number of days shown in the date strips, also read by the "Mua vé" popup script. */
    @ModelAttribute("bookingDays")
    public int bookingDays() {
        return ShowtimeService.BOOKING_WINDOW_DAYS;
    }

    @ModelAttribute("lang")
    public String lang() {
        return com.bbcinema.config.I18n.isEnglish() ? "en" : "vi";
    }

    /** Texts used by the page scripts (keys starting with "js."), in the visitor's language. */
    @ModelAttribute("jsMessages")
    public java.util.Map<String, String> jsMessages() {
        java.util.Locale locale = com.bbcinema.config.I18n.currentLocale();
        return JS_MESSAGES.computeIfAbsent(locale.getLanguage(), lang -> {
            java.util.Map<String, String> map = new java.util.TreeMap<>();
            java.util.ResourceBundle bundle = java.util.ResourceBundle.getBundle("messages", locale,
                    java.util.ResourceBundle.Control.getNoFallbackControl(java.util.ResourceBundle.Control.FORMAT_PROPERTIES));
            for (String key : bundle.keySet()) {
                if (key.startsWith("js.")) {
                    map.put(key.substring(3), bundle.getString(key));
                }
            }
            return map;
        });
    }

    private static final java.util.Map<String, java.util.Map<String, String>> JS_MESSAGES =
            new java.util.concurrent.ConcurrentHashMap<>();

    @ModelAttribute("currentPath")
    public String currentPath(HttpServletRequest request) {
        return request.getRequestURI();
    }

    /**
     * Spring does not run the @ModelAttribute methods for @ExceptionHandler views, but the error page uses the
     * same layout: fill the model by hand so a "not found" page renders instead of failing.
     */
    public void addTo(org.springframework.ui.Model model, HttpServletRequest request) {
        Long cinemaId = null;
        if (request.getCookies() != null) {
            for (jakarta.servlet.http.Cookie c : request.getCookies()) {
                if (CINEMA_COOKIE.equals(c.getName())) {
                    try {
                        cinemaId = Long.valueOf(c.getValue());
                    } catch (NumberFormatException ignored) {
                        // a bad cookie is the same as none
                    }
                }
            }
        }
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        AppUserPrincipal principal = auth != null && auth.getPrincipal() instanceof AppUserPrincipal p ? p : null;
        model.addAttribute("cinemas", cinemas());
        model.addAttribute("currentCinema", currentCinema(cinemaId));
        model.addAttribute("cinemaChosen", cinemaChosen(cinemaId));
        model.addAttribute("currentUser", principal);
        model.addAttribute("openShift", openShift(principal));
        model.addAttribute("bookingDays", bookingDays());
        model.addAttribute("lang", lang());
        model.addAttribute("jsMessages", jsMessages());
        model.addAttribute("currentPath", currentPath(request));
    }
}
