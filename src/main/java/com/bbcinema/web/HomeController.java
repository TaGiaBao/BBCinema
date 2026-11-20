package com.bbcinema.web;

import com.bbcinema.entity.Cinema;
import com.bbcinema.entity.enums.MovieStatus;
import com.bbcinema.service.CinemaService;
import com.bbcinema.service.ComboService;
import com.bbcinema.service.ContentService;
import com.bbcinema.service.MovieService;
import com.bbcinema.service.PricingService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Duration;

@Controller
@RequiredArgsConstructor
public class HomeController {

    private final MovieService movieService;
    private final ContentService contentService;
    private final CinemaService cinemaService;
    private final PricingService pricingService;
    private final ComboService comboService;
    private final com.bbcinema.service.HolidayService holidayService;

    @GetMapping("/")
    public String home(@ModelAttribute("currentCinema") Cinema currentCinema, Model model) {
        model.addAttribute("banners", contentService.activeBanners());
        model.addAttribute("nowShowing", movieService.findByStatus(MovieStatus.NOW_SHOWING));
        model.addAttribute("comingSoon", movieService.findByStatus(MovieStatus.COMING_SOON));
        model.addAttribute("special", movieService.findWithSpecialShowtimes(currentCinema == null ? null : currentCinema.getId()));
        model.addAttribute("posts", contentService.latestPosts());
        model.addAttribute("comingSoonPicks", movieService.featuredComingSoon(null));
        return "home";
    }

    /** Remembers the chosen cinema in a cookie for a year, then goes back to the page the visitor was on. */
    @GetMapping("/select-cinema")
    public String selectCinema(@RequestParam Long id,
                               @RequestParam(defaultValue = "/") String redirect,
                               HttpServletResponse response) {
        Cinema cinema = cinemaService.getById(id);
        ResponseCookie cookie = ResponseCookie.from(GlobalModelAdvice.CINEMA_COOKIE, cinema.getId().toString())
                .path("/").maxAge(Duration.ofDays(365)).sameSite("Lax").build();
        response.addHeader("Set-Cookie", cookie.toString());
        return "redirect:" + SafeRedirect.localPath(redirect, "/");
    }

    @GetMapping("/cinemas")
    public String cinemas(Model model) {
        return "cinemas";
    }

    @GetMapping("/prices")
    public String prices(Model model) {
        model.addAttribute("prices", pricingService.findAll());
        model.addAttribute("combos", comboService.findActive());
        model.addAttribute("holidays", holidayService.upcoming(12));
        addPriceAxes(model);
        return "prices";
    }

    /** The four things a price depends on, used to draw the price tables. */
    public static void addPriceAxes(Model model) {
        model.addAttribute("formats", com.bbcinema.entity.enums.ScreenFormat.values());
        model.addAttribute("seatTypes", com.bbcinema.entity.enums.SeatType.values());
        model.addAttribute("dayTypes", com.bbcinema.entity.enums.DayType.values());
        model.addAttribute("timeSlots", com.bbcinema.entity.enums.TimeSlot.values());
    }
}
