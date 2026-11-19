package com.bbcinema.entity.enums;

public enum PointType {
    EARN("Tích điểm"),
    REDEEM("Dùng điểm"),
    REVERT("Hoàn điểm");

    private final String label;

    PointType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
