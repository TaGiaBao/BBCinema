package com.bbcinema.entity.enums;

/**
 * The day part of a ticket price. HOLIDAY applies on the dates listed in the holidays table and
 * wins over WEEKEND when a holiday falls on a Saturday or Sunday.
 */
public enum DayType {
    WEEKDAY("Thứ 2 - Thứ 6"),
    WEEKEND("Thứ 7, Chủ nhật"),
    HOLIDAY("Ngày lễ");

    private final String label;

    DayType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return com.bbcinema.config.I18n.label(this, label);
    }
}
