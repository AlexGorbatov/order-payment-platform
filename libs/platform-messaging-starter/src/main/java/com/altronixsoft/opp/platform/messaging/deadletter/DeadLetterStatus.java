package com.altronixsoft.opp.platform.messaging.deadletter;

/** Lifecycle of a dead letter. */
public enum DeadLetterStatus {
    /** Persisted, waiting for an operator. */
    NEW,
    /** Re-published to its original topic through the outbox. */
    REPLAYED,
    /** Closed by an operator with a comment, without or after a replay. */
    RESOLVED
}
