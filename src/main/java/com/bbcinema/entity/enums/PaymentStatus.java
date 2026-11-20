package com.bbcinema.entity.enums;

public enum PaymentStatus {
    PENDING("Chờ xử lý"),
    SUCCESS("Thành công"),
    FAILED("Thất bại"),
    REFUNDED("Đã hoàn tiền");

    private final String label;

    PaymentStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return com.bbcinema.config.I18n.label(this, label);
    }
}
