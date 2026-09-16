package com.indore.pathome.spaces.entity;

import java.util.Locale;

public enum ParserInputSource {
    TYPED,
    DICTATED,
    MIXED,
    UNKNOWN;

    public static ParserInputSource from(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return UNKNOWN;
        }
    }
}
