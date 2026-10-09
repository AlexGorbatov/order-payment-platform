package com.altronixsoft.opp.payment.domain;

/** Processing state of a stored Stripe webhook event (architecture §6.6, §10). */
public enum WebhookEventStatus {
    /** Stored, not processed yet; due at {@code nextAttemptAt}. */
    RECEIVED,
    /** Applied. */
    PROCESSED,
    /** A type the platform does not handle, or an object it does not know. */
    IGNORED,
    /** Older than what is already applied, or a transition that is not allowed (architecture §8.3). Not an error. */
    STALE_IGNORED,
    /** An attempt failed; due again at {@code nextAttemptAt}. */
    FAILED,
    /** {@code maxAttempts} failures: needs a human. */
    DEAD
}
