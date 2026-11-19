package com.bbcinema.entity.enums;

public enum SalesChannel {
    ONLINE("Trực tuyến"),
    COUNTER("Tại quầy");

    private final String label;

    SalesChannel(String label) {
        this.label = label;
    }

    public String getLabel() {
        return com.bbcinema.config.I18n.label(this, label);
    }
}
