package com.altronixsoft.opp.payment.application;

import com.altronixsoft.opp.payment.domain.RetryPolicy;
import java.time.Duration;
import java.util.Objects;

/**
 * How a DB-backed worker of architecture §7.6 behaves ({@link CancelPaymentIntentsService}, {@link CreateRefundsService}).
 *
 * @param batchSize items claimed per run
 * @param lease how long a claimed item is not due again; must exceed the Stripe calls of a whole batch
 * @param retryPolicy backoff for transient failures; when it is used up the worker gives up on the item
 * @param deferral wait after a failure that is not the item's fault (open circuit breaker, configuration problem),
 *     without counting an attempt
 */
public record WorkerSettings(int batchSize, Duration lease, RetryPolicy retryPolicy, Duration deferral) {

    public WorkerSettings {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        requirePositive(lease, "lease");
        requirePositive(deferral, "deferral");
        Objects.requireNonNull(retryPolicy, "retryPolicy");
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
