package com.altronixsoft.opp.platform.messaging.consumer;

/**
 * Thrown by an event handler when retrying cannot help (the event is invalid for the current business state, a
 * business rule rejects it permanently). The record skips the retry topics and goes straight to the dead-letter topic
 * (architecture §7.4, ADR-0007), where operators can inspect and replay it.
 */
public class NonRetryableEventException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NonRetryableEventException(String message) {
        super(message);
    }

    public NonRetryableEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
