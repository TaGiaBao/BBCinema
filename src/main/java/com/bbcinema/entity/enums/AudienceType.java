package com.bbcinema.entity.enums;

/**
 * Who a counter ticket is for. Discounted types are sold at the counter only, where staff can see the customer and
 * ask for a student card or ID; online sales are always {@link #ADULT}.
 */
public enum AudienceType {
    ADULT("Người lớn"),
    STUDENT("HSSV"),
    CHILD("Trẻ em"),
    SENIOR("Người cao tuổi");

    private final String label;

    AudienceType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return com.bbcinema.config.I18n.label(this, label);
    }

    public boolean isDiscounted() {
        return this != ADULT;
    }
}
