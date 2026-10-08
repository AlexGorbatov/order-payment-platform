package com.altronixsoft.opp.platform.messaging.deadletter;

import java.util.UUID;

/** Base of the errors the dead-letter service reports to the admin API. */
public abstract class DeadLetterException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    protected DeadLetterException(String message) {
        super(message);
    }

    protected DeadLetterException(String message, Throwable cause) {
        super(message, cause);
    }

    /** No dead letter with this id (HTTP 404). */
    public static final class NotFound extends DeadLetterException {

        private static final long serialVersionUID = 1L;

        public NotFound(UUID id) {
            super("Dead letter " + id + " does not exist");
        }
    }

    /** The operation is not allowed in the current status (HTTP 409). */
    public static final class InvalidState extends DeadLetterException {

        private static final long serialVersionUID = 1L;

        public InvalidState(UUID id, DeadLetterStatus status, String operation) {
            super("Dead letter " + id + " is " + status + " and cannot be " + operation);
        }
    }

    /** The payload cannot be re-published as an event (HTTP 422). */
    public static final class NotReplayable extends DeadLetterException {

        private static final long serialVersionUID = 1L;

        public NotReplayable(UUID id, String reason, Throwable cause) {
            super("Dead letter " + id + " cannot be replayed: " + reason, cause);
        }
    }
}
