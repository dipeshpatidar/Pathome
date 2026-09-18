package com.indore.pathome.spaces.dto;

import java.util.Locale;

public enum AvailabilityStatus {
    READY_NOW,
    AVAILABLE_FROM_DATE,
    UNSPECIFIED;

    public static AvailabilityStatus fromExternalValue(String value) {
        if (value == null || value.isBlank()) {
            return UNSPECIFIED;
        }

        String normalized = value.trim()
                .toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        return switch (normalized) {
            case "READY_NOW", "READY_TO_MOVE", "IMMEDIATE" -> READY_NOW;
            case "AVAILABLE_FROM_DATE", "DATE", "SPECIFIC_DATE" -> AVAILABLE_FROM_DATE;
            default -> UNSPECIFIED;
        };
    }
}
