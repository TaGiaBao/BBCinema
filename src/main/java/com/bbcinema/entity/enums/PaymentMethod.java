package com.bbcinema.entity.enums;

public enum PaymentMethod {
    /** Demo only: shown to customers when no real way to pay is configured; also used by tests and the API. */
    MOCK("Thanh toán thử"),
    VNPAY("VNPay"),
    CASH("Tiền mặt"),
    /** Bank transfer by scanning a VietQR code; confirmed by SePay, by staff, or (demo) by a button. */
    BANK_QR("Chuyển khoản QR"),
    /** Bank card on the cinema's own POS terminal; staff type the approval code printed on the slip. */
    CARD("Thẻ ngân hàng"),
    MOMO("Ví MoMo");

    private final String label;

    PaymentMethod(String label) {
        this.label = label;
    }

    public String getLabel() {
        return com.bbcinema.config.I18n.label(this, label);
    }
}
