package com.bbcinema.entity.enums;

public enum PostType {
    NEWS("Tin mới"),
    PROMOTION("Ưu đãi");

    private final String label;

    PostType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return com.bbcinema.config.I18n.label(this, label);
    }
}
