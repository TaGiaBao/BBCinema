package com.bbcinema.entity.enums;

public enum MovieStatus {
    COMING_SOON("Sắp chiếu"),
    NOW_SHOWING("Đang chiếu"),
    ENDED("Ngừng chiếu");

    private final String label;

    MovieStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return com.bbcinema.config.I18n.label(this, label);
    }
}
