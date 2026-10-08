package com.altronixsoft.opp.contracts;

import java.util.Objects;

/** Required-field checks shared by the payload records. */
final class Fields {

    private Fields() {}

    static <T> T require(T value, String field) {
        return Objects.requireNonNull(value, field);
    }

    static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
