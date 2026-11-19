package com.bbcinema.entity.enums;

/** How a showtime is presented: original audio with Vietnamese subtitles, dubbed, or a Vietnamese film. */
public enum ShowLanguage {
    SUBTITLED("Phụ đề"),
    DUBBED("Lồng tiếng"),
    VIETNAMESE("Tiếng Việt");

    private final String label;

    ShowLanguage(String label) {
        this.label = label;
    }

    public String getLabel() {
        return com.bbcinema.config.I18n.label(this, label);
    }

    /** Default for a movie: Vietnamese films are shown as they are, others with subtitles. */
    public static ShowLanguage defaultFor(String movieLanguage) {
        return movieLanguage != null && movieLanguage.toLowerCase().contains("việt") ? VIETNAMESE : SUBTITLED;
    }
}
