package com.altronixsoft.opp.payment.application;

/** What the {@code RefundWorker} did with one claimed refund. */
public enum RefundOutcome {
    /** The refund exists at Stripe ({@code PENDING}); its outcome follows as a webhook. */
    CREATED_AT_STRIPE,
    /** Stripe refused it, the retries are used up, or the payment cannot be refunded: {@code FAILED} + event (F20). */
    FAILED,
    /** A transient failure; due again after a backoff. */
    RETRY_SCHEDULED,
    /** Not the refund's fault (circuit open, configuration); due again after the deferral, no attempt counted. */
    DEFERRED,
    /** The refund is no longer {@code REQUESTED}. */
    SKIPPED,
    /** Lost an optimistic-lock race; picked up again. */
    CONFLICT,
    /** Unexpected failure; the lease expires and the refund is picked up again. */
    ERROR
}
