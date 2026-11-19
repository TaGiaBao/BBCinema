package com.bbcinema.entity.enums;

public enum TimeSlot {
    BEFORE_17("Trước 17h"),
    FROM_17("Từ 17h");

    private final String label;

    TimeSlot(String label) {
        this.label = label;
    }

    public String getLabel() {
        return com.bbcinema.config.I18n.label(this, label);
    }
}
