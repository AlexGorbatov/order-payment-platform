package com.altronixsoft.opp.contracts;

import java.util.Objects;

/**
 * Static metadata of one event type at one version: what goes into the envelope, where it is published and which
 * class and JSON Schema describe the payload.
 */
public record EventDescriptor(
        String eventType,
        int eventVersion,
        String aggregateType,
        String producer,
        String topic,
        Class<? extends DomainEvent> payloadType) {

    public EventDescriptor {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(producer, "producer");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(payloadType, "payloadType");
        if (eventVersion < 1) {
            throw new IllegalArgumentException("eventVersion must be >= 1");
        }
    }

    /** Classpath location of the JSON Schema of the whole envelope for this event: {@code schemas/Type.vN.json}. */
    public String schemaResource() {
        return "schemas/" + eventType + ".v" + eventVersion + ".json";
    }
}
