package com.altronixsoft.opp.payment.application;

/** What one run of {@link ProcessWebhookEventsService} did with one claimed event. */
public enum WebhookOutcome {
    /** Applied; the event is {@code PROCESSED}. */
    PROCESSED,
    /** Older than what is known, not allowed, or a repetition; the event is {@code PROCESSED} (architecture §8.3). */
    STALE,
    /** Not a type or an object the platform handles; the event is {@code IGNORED}. */
    IGNORED,
    /** Failed; the event is {@code FAILED} and due again after a backoff. */
    RETRY_SCHEDULED,
    /** Failed for the last allowed time; the event is {@code DEAD} and needs an operator (F14). */
    DEAD,
    /** Someone else finished the event since it was claimed. */
    SKIPPED,
    /** Even recording the failure failed (database down); the lease expires and the event is retried. */
    ERROR
}
