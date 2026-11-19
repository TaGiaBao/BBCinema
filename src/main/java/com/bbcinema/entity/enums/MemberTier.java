package com.bbcinema.entity.enums;

/** Membership level, earned by spending (see app.membership in application.yml). */
public enum MemberTier {
    STANDARD("Thành viên"),
    SILVER("Bạc"),
    GOLD("Vàng"),
    DIAMOND("Kim cương");

    private final String label;

    MemberTier(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
