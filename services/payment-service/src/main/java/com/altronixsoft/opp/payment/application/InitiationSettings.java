package com.altronixsoft.opp.payment.application;

import com.altronixsoft.opp.payment.domain.RetryPolicy;
import java.time.Duration;
import java.util.Objects;

/**
 * How {@link InitiatePaymentsService} behaves.
 *
 * @param batchSize payments claimed per run
 * @param lease how long a claimed payment is not due again; it must exceed the longest Stripe call (up to
 *     {@code (maxNetworkRetries + 1) × readTimeout}) for the whole batch, otherwise another instance may pick up a payment
 *     that is still being worked on, which is harmless (same idempotency key, optimistic lock) but wasteful
 * @param idempotencyWindow how long a payment may wait in {@code CREATED}. Stripe forgets an idempotency key after
 *     <em>at least</em> 24 hours; a payment older than {@code window} is failed instead of retried, because retrying with
 *     a forgotten key could create a second PaymentIntent for the same order (F08). Default 23 hours.
 * @param retryPolicy backoff for transient failures
 * @param deferral how long to wait after a failure that is not the payment's fault (an open circuit breaker, a
 *     configuration problem) without counting an attempt
 */
public record InitiationSettings(
        int batchSize, Duration lease, Duration idempotencyWindow, RetryPolicy retryPolicy, Duration deferral) {

    public InitiationSettings {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        requirePositive(lease, "lease");
        requirePositive(idempotencyWindow, "idempotencyWindow");
        requirePositive(deferral, "deferral");
        Objects.requireNonNull(retryPolicy, "retryPolicy");
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
