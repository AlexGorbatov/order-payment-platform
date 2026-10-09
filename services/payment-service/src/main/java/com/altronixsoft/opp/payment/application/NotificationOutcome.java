package com.altronixsoft.opp.payment.application;

/** What {@link StripeNotificationHandler} did with one notification. */
public enum NotificationOutcome {
    /** The payment or refund changed (and its events went to the outbox). */
    APPLIED,
    /**
     * The report is older than what was applied, the transition is not allowed, or it repeats what is already known
     * (architecture §8.3). Not an error: the event counts as processed.
     */
    STALE,
    /** A type the platform does not handle, or an object it does not know. */
    IGNORED
}
