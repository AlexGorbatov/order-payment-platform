package com.altronixsoft.opp.payment.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * One entry of the payment's status history (table {@code payment_status_history}).
 *
 * @param from previous status; {@code null} for the entry that creates the payment
 * @param stripeEventId the webhook event that caused it, if any
 * @param occurredAt when Stripe says it happened (for Stripe-sourced changes) or when we decided it
 */
public record PaymentStatusChange(
        PaymentStatus from, PaymentStatus to, PaymentStatusSource source, String stripeEventId, Instant occurredAt) {

    public PaymentStatusChange {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
