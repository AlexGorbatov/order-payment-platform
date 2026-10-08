package com.altronixsoft.opp.contracts;

/** An envelope could not be written or read: malformed JSON, missing or invalid fields, or an invalid payload. */
public class EventSerdeException extends RuntimeException {

    public EventSerdeException(String message) {
        super(message);
    }

    public EventSerdeException(String message, Throwable cause) {
        super(message, cause);
    }
}
