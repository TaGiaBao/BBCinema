package com.bbcinema.util;

public final class TextUtil {

    private TextUtil() {
    }

    /** Returns null for null or blank text, otherwise the trimmed text. Used for optional filters. */
    public static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
