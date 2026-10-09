package com.altronixsoft.opp.payment.application;

import java.time.Duration;

/**
 * How {@link ReconcilePaymentsService} behaves (architecture §8.4).
 *
 * @param staleAfter a payment is checked when it has not changed for this long (10 minutes): webhooks normally arrive
 *     within seconds, so a payment that has been quiet this long may have lost one. The same period separates two
 *     checks of the same payment.
 * @param batchSize payments checked per run at most
 */
public record ReconciliationSettings(Duration staleAfter, int batchSize) {

    public ReconciliationSettings {
        if (staleAfter == null || staleAfter.isZero() || staleAfter.isNegative()) {
            throw new IllegalArgumentException("staleAfter must be positive");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
    }
}
