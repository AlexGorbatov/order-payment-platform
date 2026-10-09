package com.altronixsoft.opp.payment.application;

/** What one run of the initiation worker did to one payment. */
public enum InitiationOutcome {
    /** The PaymentIntent was created and recorded; {@code PaymentInitiated} is published. */
    INITIATED,
    /** A transient failure: the payment is due again after a backoff, with the same idempotency key. */
    RETRY_SCHEDULED,
    /** Stripe rejected the request for good: {@code INITIATION_FAILED} and {@code PaymentInitiationFailed} (F07). */
    FAILED_PERMANENT,
    /** Too many transient failures: {@code INITIATION_FAILED}. */
    FAILED_EXHAUSTED,
    /** Waiting in {@code CREATED} past the idempotency window: {@code INITIATION_FAILED} without a call (F08). */
    FAILED_WINDOW_ELAPSED,
    /** The circuit breaker is open: waits without counting an attempt. */
    DEFERRED_CIRCUIT_OPEN,
    /** Our credentials or configuration are wrong, or an idempotency key clashed: waits, alerts, counts no attempt. */
    DEFERRED_CONFIG,
    /** The payment was cancelled while the worker called Stripe; the orphaned PaymentIntent is cancelled. */
    SKIPPED_NO_LONGER_CREATED,
    /** The payment changed concurrently and could not be updated; its lease expires and another run repeats it. */
    CONFLICT,
    /** An unexpected failure; logged, the lease expires and another run repeats it. */
    ERROR
}
