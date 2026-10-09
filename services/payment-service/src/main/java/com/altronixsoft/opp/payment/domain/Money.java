package com.altronixsoft.opp.payment.domain;

import java.util.Currency;
import java.util.Objects;

/** An amount in the minor unit of its currency (cents for EUR), never a floating point number; never negative. */
public record Money(long amountMinor, Currency currency) {

    public Money {
        Objects.requireNonNull(currency, "currency");
        if (amountMinor < 0) {
            throw new IllegalArgumentException("amount must not be negative, got " + amountMinor);
        }
    }

    /** @param currencyCode upper-case ISO-4217 code, for example {@code EUR} */
    public static Money of(long amountMinor, String currencyCode) {
        return new Money(amountMinor, Currency.getInstance(currencyCode));
    }

    public String currencyCode() {
        return currency.getCurrencyCode();
    }

    public boolean isPositive() {
        return amountMinor > 0;
    }

    @Override
    public String toString() {
        return amountMinor + " " + currency.getCurrencyCode() + " (minor units)";
    }
}
