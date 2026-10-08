package com.altronixsoft.opp.platform.messaging.outbox;

/** A record could not be delivered (timeout, broker error, interruption). */
public class OutboxSendException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public OutboxSendException(String message) {
        super(message);
    }

    public OutboxSendException(String message, Throwable cause) {
        super(message, cause);
    }
}
