package com.altronixsoft.opp.order.domain;

import java.util.Currency;
import java.util.Objects;

/**
 * An amount of money in the minor unit of its currency (cents for EUR), never a floating point number. Amounts are not
 * negative; arithmetic is exact and refuses to mix currencies or to overflow.
 */
public record Money(long amountMinor, Currency currency) {

    public Money {
        Objects.requireNonNull(currency, "currency");
        if (amountMinor < 0) {
            throw new IllegalArgumentException("amount must not be negative, got " + amountMinor);
        }
    }

    /**
     * @param currencyCode upper-case ISO-4217 code, for example {@code EUR}
     * @throws IllegalArgumentException the code is not a known ISO-4217 code
     */
    public static Money of(long amountMinor, String currencyCode) {
        return new Money(amountMinor, Currency.getInstance(currencyCode));
    }

    public static Money zero(Currency currency) {
        return new Money(0, currency);
    }

    public String currencyCode() {
        return currency.getCurrencyCode();
    }

    public boolean isPositive() {
        return amountMinor > 0;
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(amountMinor, other.amountMinor), currency);
    }

    public Money times(int quantity) {
        if (quantity < 0) {
            throw new IllegalArgumentException("quantity must not be negative, got " + quantity);
        }
        return new Money(Math.multiplyExact(amountMinor, (long) quantity), currency);
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("currencies differ: " + currency + " and " + other.currency);
        }
    }

    @Override
    public String toString() {
        return amountMinor + " " + currency.getCurrencyCode() + " (minor units)";
    }
}
