package com.oliver.erydon.client.model;

enum SynapheiaMode {
    SYNAPHEIA("synapheia");

    private final String configValue;

    SynapheiaMode(String configValue) {
        this.configValue = configValue;
    }

    static SynapheiaMode configured() {
        return SYNAPHEIA;
    }

    static SynapheiaMode fromConfig(String value) {
        if (value != null && SYNAPHEIA.configValue.equalsIgnoreCase(value.trim())) {
            return SYNAPHEIA;
        }
        throw new IllegalArgumentException("Synapheia is the permanent ERYDON CTM engine.");
    }

    String configValue() {
        return configValue;
    }

    boolean isEnabled() {
        return true;
    }
}
