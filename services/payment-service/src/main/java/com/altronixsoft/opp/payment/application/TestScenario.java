package com.altronixsoft.opp.payment.application;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * What the test-support endpoint can make Stripe do, by confirming the PaymentIntent with one of Stripe's test payment
 * methods (architecture §8.5). The result is never returned as a state change: it arrives as a webhook like any real
 * payment.
 */
public enum TestScenario {
    /** A successful card payment. */
    SUCCESS("success", "pm_card_visa"),
    /** A generic decline. */
    DECLINE("decline", "pm_card_chargeDeclined"),
    /** A decline for insufficient funds. */
    INSUFFICIENT_FUNDS("insufficient_funds", "pm_card_chargeDeclinedInsufficientFunds"),
    /** The card requires 3-D Secure authentication. */
    REQUIRES_3DS("requires_3ds", "pm_card_authenticationRequired"),
    /** A payment that is later disputed. */
    DISPUTE("dispute", "pm_card_createDispute"),
    /** A payment whose refund will fail. */
    REFUND_FAIL("refund_fail", "pm_card_refundFail");

    private final String parameter;
    private final String paymentMethodId;

    TestScenario(String parameter, String paymentMethodId) {
        this.parameter = parameter;
        this.paymentMethodId = paymentMethodId;
    }

    /** The value of the {@code scenario} query parameter. */
    public String parameter() {
        return parameter;
    }

    /** The Stripe test payment method used to confirm. */
    public String paymentMethodId() {
        return paymentMethodId;
    }

    /** @return the scenario named by a query parameter value, ignoring case */
    public static Optional<TestScenario> fromParameter(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(s -> s.parameter.equals(normalized))
                .findFirst();
    }
}
