package com.altronixsoft.opp.payment.domain;

/** What a failed attempt of queued work leads to. */
public enum RetryDecision {
    /** The work is due again at the item's {@code nextAttemptAt}. */
    RETRY_SCHEDULED,
    /** {@code maxAttempts} failures: no more automatic attempts; the caller decides what the item becomes. */
    EXHAUSTED
}
