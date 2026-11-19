package com.bbcinema.entity.enums;

public enum DayType {
    WEEKDAY("Thứ 2 - Thứ 6"),
    WEEKEND("Thứ 7, Chủ nhật");

    private final String label;

    DayType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return com.bbcinema.config.I18n.label(this, label);
    }
}
