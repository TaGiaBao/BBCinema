package com.bbcinema.entity.enums;

/** How a gift discount is given: a percent of the order (optionally capped) or a fixed amount in VND. */
public enum DiscountType {
    PERCENT("Giảm theo %"),
    AMOUNT("Giảm số tiền cố định");

    private final String label;

    DiscountType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return com.bbcinema.config.I18n.label(this, label);
    }
}
