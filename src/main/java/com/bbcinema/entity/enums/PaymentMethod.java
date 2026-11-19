package com.bbcinema.entity.enums;

public enum PaymentMethod {
    MOCK("Thanh toán thử"),
    VNPAY("VNPay"),
    CASH("Tiền mặt");

    private final String label;

    PaymentMethod(String label) {
        this.label = label;
    }

    public String getLabel() {
        return com.bbcinema.config.I18n.label(this, label);
    }
}
