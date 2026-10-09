package com.altronixsoft.opp.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The mapping of Stripe's PaymentIntent statuses of architecture §5.2. */
class StripePaymentIntentStatusTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "requires_payment_method, REQUIRES_PAYMENT_METHOD",
        "requires_confirmation, REQUIRES_PAYMENT_METHOD",
        "requires_action, REQUIRES_ACTION",
        "processing, PROCESSING",
        "succeeded, SUCCEEDED",
        "canceled, CANCELED"
    })
    void mapsTheStatusesOfTheSpec(String stripe, PaymentStatus expected) {
        assertThat(StripePaymentIntentStatus.toPaymentStatus(stripe)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"requires_capture"})
    void manualCaptureIsAConfigurationError(String stripe) {
        assertThatThrownBy(() -> StripePaymentIntentStatus.toPaymentStatus(stripe))
                .isExactlyInstanceOf(StripeConfigurationException.class)
                .hasMessageContaining("requires_capture")
                .isInstanceOf(PaymentDomainException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"", "cancelled", "SUCCEEDED", "Succeeded", "requires_payment_methods", "refunded", "created"})
    @NullSource
    void anythingElseIsUnknownAndNotGuessed(String stripe) {
        assertThatThrownBy(() -> StripePaymentIntentStatus.toPaymentStatus(stripe))
                .isExactlyInstanceOf(UnknownStripeStatusException.class)
                .satisfies(e -> assertThat(((UnknownStripeStatusException) e).stripeStatus())
                        .isEqualTo(stripe));
    }
}
