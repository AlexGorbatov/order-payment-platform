package com.altronixsoft.opp.payment.application;

/** What the {@code PaymentCancellationWorker} did with one claimed payment. */
public enum CancellationOutcome {
    /** Stripe accepted the cancellation; {@code payment_intent.canceled} will follow as a webhook. */
    CANCEL_SENT,
    /**
     * Stripe refused: the PaymentIntent is already {@code processing} or {@code succeeded} (F19). Not retried; the
     * success arrives as a webhook and order-service refunds it (F18).
     */
    TOO_LATE,
    /** Stripe refused for another permanent reason, or the retries are used up; the payment waits for Stripe's report. */
    GAVE_UP,
    /** A transient failure; due again after a backoff. */
    RETRY_SCHEDULED,
    /** Not the payment's fault (circuit open, configuration); due again after the deferral, no attempt counted. */
    DEFERRED,
    /** The payment no longer needs cancelling (Stripe reported a final status meanwhile). */
    SKIPPED,
    /** Lost an optimistic-lock race; picked up again. */
    CONFLICT,
    /** Unexpected failure; the lease expires and the payment is picked up again. */
    ERROR
}
