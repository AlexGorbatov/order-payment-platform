package com.altronixsoft.opp.contracts;

import java.util.Currency;
import java.util.Objects;
import java.util.regex.Pattern;

/** Validation of money fields: amounts are minor units ({@code long}), currencies are upper-case ISO-4217 codes. */
public final class Currencies {

    private static final Pattern CODE = Pattern.compile("[A-Z]{3}");

    private Currencies() {}

    /** Returns {@code code} if it is an upper-case ISO-4217 code known to the JDK, otherwise throws. */
    public static String requireIso4217(String code) {
        Objects.requireNonNull(code, "currency must not be null");
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("currency must be an upper-case ISO-4217 code, got '" + code + "'");
        }
        try {
            Currency.getInstance(code);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown ISO-4217 currency '" + code + "'", e);
        }
        return code;
    }

    /** Returns {@code amountMinor} if it is strictly positive, otherwise throws. */
    public static long requirePositive(long amountMinor, String field) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException(field + " must be positive (minor units), got " + amountMinor);
        }
        return amountMinor;
    }
}
