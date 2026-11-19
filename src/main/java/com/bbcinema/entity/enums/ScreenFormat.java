package com.bbcinema.entity.enums;

public enum ScreenFormat {
    TWO_D("2D"),
    THREE_D("3D");

    private final String label;

    ScreenFormat(String label) {
        this.label = label;
    }

    public String getLabel() {
        return com.bbcinema.config.I18n.label(this, label);
    }
}
