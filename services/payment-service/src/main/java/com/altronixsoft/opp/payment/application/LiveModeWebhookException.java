package com.altronixsoft.opp.payment.application;

/**
 * A correctly signed webhook from <b>live</b> mode (F13). This platform handles test mode only (architecture §8.1), so
 * the event is refused and not stored; it means a live key or endpoint is pointed at this system, which needs a human.
 */
public class LiveModeWebhookException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String eventId;
    private final String type;

    public LiveModeWebhookException(String eventId, String type) {
        super("Live-mode webhook " + eventId + " (" + type + ") refused: this platform runs in test mode only");
        this.eventId = eventId;
        this.type = type;
    }

    public String eventId() {
        return eventId;
    }

    public String type() {
        return type;
    }
}
