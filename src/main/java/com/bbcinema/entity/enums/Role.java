package com.bbcinema.entity.enums;

public enum Role {
    USER("Thành viên"),
    STAFF("Nhân viên"),
    /** Counter manager of one cinema: opens and closes till shifts in person, requests stock, runs the programme. */
    MANAGER("Quản lý quầy"),
    /** General manager: the whole chain online (cinemas, prices, accounts, approvals), never the till. */
    ADMIN("Quản lý tổng");

    private final String label;

    Role(String label) {
        this.label = label;
    }

    public String getLabel() {
        return com.bbcinema.config.I18n.label(this, label);
    }

    /** Authority name used by Spring Security, e.g. ROLE_ADMIN. */
    public String authority() {
        return "ROLE_" + name();
    }

    /** Staff, counter managers and the general manager use the internal area. */
    public boolean isInternal() {
        return this == STAFF || this == MANAGER || this == ADMIN;
    }
}
