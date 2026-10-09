package com.altronixsoft.opp.payment.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

/** Builders and oracles shared by the tests of the payment domain. */
public final class PaymentFixtures {

    public static final Instant T0 = Instant.parse("2026-10-08T12:00:00Z");

    public static final UUID ORDER_ID = UUID.fromString("0199e0a0-1111-7000-8000-000000000001");
    public static final String PI = "pi_3Test";

    /**
     * The state diagram of architecture §5.2, transcribed independently of the code under test: every allowed
     * transition as {@code FROM->TO}.
     */
    public static final Set<String> DIAGRAM = Set.of(
            "CREATED->REQUIRES_PAYMENT_METHOD",
            "CREATED->INITIATION_FAILED",
            "CREATED->CANCELED",
            "REQUIRES_PAYMENT_METHOD->REQUIRES_ACTION",
            "REQUIRES_PAYMENT_METHOD->PROCESSING",
            "REQUIRES_PAYMENT_METHOD->SUCCEEDED",
            "REQUIRES_ACTION->PROCESSING",
            "REQUIRES_ACTION->SUCCEEDED",
            "REQUIRES_ACTION->REQUIRES_PAYMENT_METHOD",
            "PROCESSING->SUCCEEDED",
            "PROCESSING->REQUIRES_PAYMENT_METHOD",
            "REQUIRES_PAYMENT_METHOD->CANCELED",
            "REQUIRES_ACTION->CANCELED",
            "SUCCEEDED->REFUNDED");

    private PaymentFixtures() {}

    public static Instant at(int secondsAfterStart) {
        return T0.plusSeconds(secondsAfterStart);
    }

    public static Money eur(long minor) {
        return Money.of(minor, "EUR");
    }

    /** A fresh payment of 30.97 EUR for {@link #ORDER_ID}, created at {@link #T0}. */
    public static Payment created() {
        return Payment.create(UUID.randomUUID(), ORDER_ID, "customer-1", eur(3097), T0);
    }

    /**
     * A payment in {@code status}, reached only through legal commands and reports. The ordering watermark ends at
     * {@code at(10)} for the statuses that come from Stripe, so reports at {@code at(11)} and later are current.
     */
    public static Payment inStatus(PaymentStatus status) {
        Payment payment = created();
        switch (status) {
            case CREATED -> {}
            case INITIATION_FAILED -> payment.markInitiationFailed("card_declined", "no", at(1));
            case CANCELED -> payment.requestCancel(at(1));
            case REQUIRES_PAYMENT_METHOD -> payment.attachPaymentIntent(PI, at(10));
            case REQUIRES_ACTION -> {
                payment.attachPaymentIntent(PI, at(5));
                payment.applyStripeStatus("requires_action", at(10), PaymentStatusSource.WEBHOOK);
            }
            case PROCESSING -> {
                payment.attachPaymentIntent(PI, at(5));
                payment.applyStripeStatus("processing", at(10), PaymentStatusSource.WEBHOOK);
            }
            case SUCCEEDED -> {
                payment.attachPaymentIntent(PI, at(5));
                payment.applyStripeStatus("succeeded", at(10), PaymentStatusSource.WEBHOOK);
            }
            case REFUNDED -> {
                payment.attachPaymentIntent(PI, at(5));
                payment.applyStripeStatus("succeeded", at(8), PaymentStatusSource.WEBHOOK);
                payment.markRefunded(
                        UUID.fromString("0199e0a0-7777-7000-8000-000000000007"),
                        "re_test_1",
                        at(10),
                        PaymentStatusSource.WEBHOOK,
                        "evt_refund");
            }
        }
        return payment;
    }

    /** A payment with a PaymentIntent that is cancelable ({@code REQUIRES_PAYMENT_METHOD}), created at {@link #T0}. */
    public static Payment withPaymentIntent() {
        return inStatus(PaymentStatus.REQUIRES_PAYMENT_METHOD);
    }

    public static Refund requestedRefund() {
        return Refund.request(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), eur(3097), "ADMIN", T0);
    }

    /** A "random" source that always answers {@code value} in {@code [0, 1)}. */
    public static RandomGenerator fixedRandom(double value) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                throw new UnsupportedOperationException();
            }

            @Override
            public double nextDouble() {
                return value;
            }
        };
    }

    public static Duration seconds(long seconds) {
        return Duration.ofSeconds(seconds);
    }
}
