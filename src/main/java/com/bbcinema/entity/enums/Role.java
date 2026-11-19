package com.bbcinema.entity.enums;

public enum Role {
    USER("Thành viên"),
    STAFF("Nhân viên"),
    ADMIN("Quản trị viên");

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
}
