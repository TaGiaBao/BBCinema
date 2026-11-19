package com.bbcinema.entity.enums;

public enum AgeRating {
    P("Mọi lứa tuổi", 0),
    K("Dưới 13 tuổi cần người lớn đi kèm", 0),
    T13("Từ 13 tuổi trở lên", 13),
    T16("Từ 16 tuổi trở lên", 16),
    T18("Từ 18 tuổi trở lên", 18);

    private final String label;
    private final int minAge;

    AgeRating(String label, int minAge) {
        this.label = label;
        this.minAge = minAge;
    }

    public String getLabel() {
        return com.bbcinema.config.I18n.label(this, label);
    }

    public int getMinAge() {
        return minAge;
    }
}
