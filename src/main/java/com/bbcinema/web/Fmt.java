package com.bbcinema.web;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Locale;

/** Formatting helpers for templates, used as ${@fmt.money(price)}. */
@Component("fmt")
public class Fmt {

    private static final String[] DAY_NAMES = {"Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy", "Chủ Nhật"};

    /** 120000 -> "120.000đ" */
    public String money(Number amount) {
        if (amount == null) {
            return "0đ";
        }
        BigDecimal value = amount instanceof BigDecimal d ? d : new BigDecimal(amount.toString());
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator('.');
        return new DecimalFormat("#,##0", symbols).format(value) + "đ";
    }

    public String dayName(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return DAY_NAMES[day.getValue() - 1];
    }

    /** "Hôm nay", "Ngày mai" or the day name. */
    public String relativeDay(LocalDate date, LocalDate today) {
        if (date.equals(today)) {
            return "Hôm nay";
        }
        if (date.equals(today.plusDays(1))) {
            return "Ngày mai";
        }
        return dayName(date);
    }

    /** "02:15" style duration for a number of minutes, e.g. 135 -> "2 giờ 15 phút". */
    public String duration(int minutes) {
        int hours = minutes / 60;
        int rest = minutes % 60;
        if (hours == 0) {
            return rest + " phút";
        }
        return rest == 0 ? hours + " giờ" : hours + " giờ " + rest + " phút";
    }

    /** Turns a YouTube watch link into an embeddable link; returns null for other links. */
    public String youtubeEmbed(String url) {
        if (url == null) {
            return null;
        }
        String id = null;
        if (url.contains("youtube.com/watch?v=")) {
            id = url.substring(url.indexOf("v=") + 2);
        } else if (url.contains("youtu.be/")) {
            id = url.substring(url.indexOf("youtu.be/") + 9);
        } else if (url.contains("youtube.com/embed/")) {
            id = url.substring(url.indexOf("embed/") + 6);
        }
        if (id == null) {
            return null;
        }
        int cut = id.indexOf('&');
        if (cut < 0) {
            cut = id.indexOf('?');
        }
        if (cut >= 0) {
            id = id.substring(0, cut);
        }
        return id.matches("[A-Za-z0-9_-]{6,20}") ? "https://www.youtube.com/embed/" + id : null;
    }
}
