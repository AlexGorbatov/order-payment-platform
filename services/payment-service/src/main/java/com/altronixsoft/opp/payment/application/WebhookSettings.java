package com.altronixsoft.opp.payment.application;

import com.altronixsoft.opp.payment.domain.RetryPolicy;
import java.time.Duration;
import java.util.Objects;

/**
 * How {@link ProcessWebhookEventsService} behaves.
 *
 * @param batchSize events claimed per run
 * @param lease how long a claimed event is not due again; it must exceed the time to process the whole batch, otherwise
 *     another instance may take an event that is still being worked on (harmless: optimistic locking and the event's
 *     status decide, but wasteful)
 * @param retryPolicy backoff after a failed attempt; after {@code maxAttempts} failures the event is {@code DEAD}
 */
public record WebhookSettings(int batchSize, Duration lease, RetryPolicy retryPolicy) {

    public WebhookSettings {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        if (lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("lease must be positive");
        }
        Objects.requireNonNull(retryPolicy, "retryPolicy");
    }
}
