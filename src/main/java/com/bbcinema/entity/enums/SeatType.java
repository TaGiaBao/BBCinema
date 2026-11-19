package com.bbcinema.entity.enums;

public enum SeatType {
    STANDARD("Ghế thường"),
    VIP("Ghế VIP"),
    COUPLE("Ghế đôi");

    private final String label;

    SeatType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
