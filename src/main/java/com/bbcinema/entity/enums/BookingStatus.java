package com.bbcinema.entity.enums;

public enum BookingStatus {
    PENDING("Chờ thanh toán"),
    PAID("Đã thanh toán"),
    CANCELLED("Đã huỷ"),
    EXPIRED("Hết hạn");

    private final String label;

    BookingStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return com.bbcinema.config.I18n.label(this, label);
    }
}
