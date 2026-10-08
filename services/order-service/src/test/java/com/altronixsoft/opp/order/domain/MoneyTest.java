package com.altronixsoft.opp.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

    @Test
    void holdsMinorUnitsAndCurrency() {
        Money money = Money.of(1299, "EUR");

        assertThat(money.amountMinor()).isEqualTo(1299);
        assertThat(money.currencyCode()).isEqualTo("EUR");
        assertThat(money.currency()).isEqualTo(Currency.getInstance("EUR"));
        assertThat(money.isPositive()).isTrue();
        assertThat(money).isEqualTo(Money.of(1299, "EUR")).isNotEqualTo(Money.of(1299, "USD"));
    }

    @Test
    void zeroIsNotPositive() {
        assertThat(Money.zero(Currency.getInstance("EUR")).isPositive()).isFalse();
        assertThat(Money.of(0, "EUR").amountMinor()).isZero();
    }

    @Test
    void negativeAmountsAreRejected() {
        assertThatThrownBy(() -> Money.of(-1, "EUR")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"eur", "EURO", "", "XX1", "ZZZ"})
    void onlyKnownUpperCaseIsoCodesAreAccepted(String code) {
        assertThatThrownBy(() -> Money.of(1, code)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addsAndMultipliesExactly() {
        assertThat(Money.of(1299, "EUR").plus(Money.of(499, "EUR"))).isEqualTo(Money.of(1798, "EUR"));
        assertThat(Money.of(1299, "EUR").times(3)).isEqualTo(Money.of(3897, "EUR"));
        assertThat(Money.of(1299, "EUR").times(0)).isEqualTo(Money.of(0, "EUR"));
    }

    @Test
    void refusesToMixCurrencies() {
        assertThatThrownBy(() -> Money.of(1, "EUR").plus(Money.of(1, "USD")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currencies differ");
    }

    @Test
    void refusesNegativeQuantitiesAndOverflow() {
        assertThatThrownBy(() -> Money.of(1, "EUR").times(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of(Long.MAX_VALUE, "EUR").times(2)).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Money.of(Long.MAX_VALUE, "EUR").plus(Money.of(1, "EUR")))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void nullCurrencyIsRejected() {
        assertThatThrownBy(() -> new Money(1, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void toStringNamesTheUnit() {
        assertThat(Money.of(1299, "EUR").toString()).isEqualTo("1299 EUR (minor units)");
    }
}
