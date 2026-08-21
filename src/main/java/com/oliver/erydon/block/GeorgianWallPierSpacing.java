package com.oliver.erydon.block;

enum GeorgianWallPierSpacing {
    EVERY_2(5, 2, "every_2"),
    EVERY_3(1, 3, "every_3"),
    EVERY_4(0, 4, "every_4"),
    EVERY_5(2, 5, "every_5"),
    JOINTS_ONLY(3, 0, "joints_only"),
    NONE(4, 0, "none");

    private final int storedValue;
    private final int interval;
    private final String translationSuffix;

    GeorgianWallPierSpacing(int storedValue, int interval, String translationSuffix) {
        this.storedValue = storedValue;
        this.interval = interval;
        this.translationSuffix = translationSuffix;
    }

    int storedValue() {
        return storedValue;
    }

    int interval() {
        return interval;
    }

    boolean piersEnabled() {
        return this != NONE;
    }

    String translationKey() {
        return "message.erydon.georgian_wall.pier_spacing." + translationSuffix;
    }

    GeorgianWallPierSpacing next() {
        return switch (this) {
            case EVERY_2 -> EVERY_3;
            case EVERY_3 -> EVERY_4;
            case EVERY_4 -> EVERY_5;
            case EVERY_5 -> JOINTS_ONLY;
            case JOINTS_ONLY -> NONE;
            case NONE -> EVERY_2;
        };
    }

    static GeorgianWallPierSpacing fromStoredValue(int value) {
        return switch (value) {
            case 1 -> EVERY_3;
            case 2 -> EVERY_5;
            case 3 -> JOINTS_ONLY;
            case 4 -> NONE;
            case 5 -> EVERY_2;
            default -> EVERY_4;
        };
    }
}
