package com.altronixsoft.opp.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PayloadValidationTest {

    @ParameterizedTest
    @ValueSource(strings = {"EUR", "USD", "GBP", "JPY", "PLN", "UAH"})
    void acceptsIso4217Codes(String code) {
        assertThat(Currencies.requireIso4217(code)).isEqualTo(code);
    }

    @ParameterizedTest
    @ValueSource(strings = {"eur", "Eur", "EU", "EURO", "", " ", "E1R", "ZZZ", "AAA"})
    void rejectsEverythingElse(String code) {
        assertThatThrownBy(() -> Currencies.requireIso4217(code)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullCurrency() {
        assertThatThrownBy(() -> Currencies.requireIso4217(null)).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void amountsMustBePositive(long amount) {
        assertThatThrownBy(() -> new OrderCreated(Samples.ORDER_ID, "c1", amount, "EUR", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amountMinor");
        assertThatThrownBy(() -> new OrderRefundRequested(
                        Samples.ORDER_ID, Samples.REFUND_REQUEST_ID, amount, "EUR", RefundReason.ADMIN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        new PaymentSucceeded(Samples.PAYMENT_ID, Samples.ORDER_ID, amount, "EUR", "pi_1", Samples.NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentRefunded(
                        Samples.PAYMENT_ID, Samples.ORDER_ID, Samples.REFUND_REQUEST_ID, "re_1", amount))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void moneyEventsRejectInvalidCurrency() {
        assertThatThrownBy(() -> new OrderCreated(Samples.ORDER_ID, "c1", 100, "eur", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency");
        assertThatThrownBy(() ->
                        new PaymentSucceeded(Samples.PAYMENT_ID, Samples.ORDER_ID, 100, "XYZ", "pi_1", Samples.NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderRefundRequested(
                        Samples.ORDER_ID, Samples.REFUND_REQUEST_ID, 100, null, RefundReason.ADMIN))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void requiredFieldsAreEnforced() {
        assertThatThrownBy(() -> new OrderCreated(null, "c1", 100, "EUR", 1)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OrderCreated(Samples.ORDER_ID, " ", 100, "EUR", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderCreated(Samples.ORDER_ID, "c1", 100, "EUR", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderCancelled(Samples.ORDER_ID, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentInitiated(Samples.PAYMENT_ID, Samples.ORDER_ID, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentAttemptFailed(Samples.PAYMENT_ID, Samples.ORDER_ID, "x", " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void declineCodeIsOptional() {
        assertThatCode(() -> new PaymentAttemptFailed(Samples.PAYMENT_ID, Samples.ORDER_ID, "card_declined", null))
                .doesNotThrowAnyException();
    }
}
