package com.bbcinema.util;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Turns Vietnamese titles into URL friendly slugs, e.g. "Hành Tinh Cát" -> "hanh-tinh-cat". */
public final class SlugUtil {

    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");

    private SlugUtil() {
    }

    public static String toSlug(String input) {
        if (input == null) {
            return "";
        }
        String text = input.trim().replace('đ', 'd').replace('Đ', 'D');
        text = Normalizer.normalize(text, Normalizer.Form.NFD);
        text = DIACRITICS.matcher(text).replaceAll("");
        text = text.toLowerCase(Locale.ROOT);
        text = NON_ALPHANUMERIC.matcher(text).replaceAll("-");
        return text.replaceAll("(^-+)|(-+$)", "");
    }
}
