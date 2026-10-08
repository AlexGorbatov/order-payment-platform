package com.altronixsoft.opp.contracts;

/**
 * The envelope is well-formed but its {@code eventType}/{@code eventVersion} is not known to this build. Consumers
 * skip such events instead of dead-lettering them (architecture §9.3): a newer producer may legitimately publish
 * types or versions this consumer has not been upgraded to understand.
 */
public class UnknownEventTypeException extends EventSerdeException {

    private static final long serialVersionUID = 1L;

    private final String eventType;
    private final int eventVersion;

    public UnknownEventTypeException(String eventType, int eventVersion) {
        super("Unknown event type '" + eventType + "' version " + eventVersion + " (not registered in EventCatalog)");
        this.eventType = eventType;
        this.eventVersion = eventVersion;
    }

    public String eventType() {
        return eventType;
    }

    public int eventVersion() {
        return eventVersion;
    }
}
