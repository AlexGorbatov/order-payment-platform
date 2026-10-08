package com.altronixsoft.opp.contracts;

import com.github.f4b6a3.uuid.UuidCreator;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Common wrapper of every event on Kafka (architecture §9.2).
 *
 * @param eventId unique id (UUIDv7, time-ordered); the inbox deduplicates on it
 * @param eventType event type name, see {@link EventTypes}
 * @param eventVersion schema version of this event type
 * @param aggregateType {@code Order} or {@code Payment}
 * @param aggregateId id of the aggregate that emitted the event
 * @param partitionKey Kafka key: the order id, so all events of one order are ordered
 * @param occurredAt when the event happened (UTC, microsecond precision)
 * @param producer emitting service
 * @param correlationId id shared by every event and request of one business flow
 * @param causationId id of the event or request that directly triggered this event; may be {@code null}
 * @param payload the event data
 */
public record EventEnvelope<T extends DomainEvent>(
        UUID eventId,
        String eventType,
        int eventVersion,
        String aggregateType,
        UUID aggregateId,
        String partitionKey,
        Instant occurredAt,
        String producer,
        UUID correlationId,
        UUID causationId,
        T payload) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(partitionKey, "partitionKey");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(producer, "producer");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(payload, "payload");
        if (eventVersion < 1) {
            throw new IllegalArgumentException("eventVersion must be >= 1, got " + eventVersion);
        }
        if (eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        if (partitionKey.isBlank()) {
            throw new IllegalArgumentException("partitionKey must not be blank");
        }
    }

    /** Wraps {@code payload} with a fresh UUIDv7 id and the timestamp of {@code clock}. */
    public static <T extends DomainEvent> EventEnvelope<T> create(
            T payload, UUID correlationId, UUID causationId, Clock clock) {
        return create(payload, correlationId, causationId, clock, EventEnvelope::newEventId);
    }

    /** Same as {@link #create(DomainEvent, UUID, UUID, Clock)} with an explicit id source (for deterministic tests). */
    public static <T extends DomainEvent> EventEnvelope<T> create(
            T payload, UUID correlationId, UUID causationId, Clock clock, Supplier<UUID> eventIds) {
        Objects.requireNonNull(payload, "payload");
        EventDescriptor descriptor = EventCatalog.of(payload.getClass().asSubclass(DomainEvent.class));
        return new EventEnvelope<>(
                eventIds.get(),
                descriptor.eventType(),
                descriptor.eventVersion(),
                descriptor.aggregateType(),
                payload.aggregateId(),
                payload.orderId().toString(),
                clock.instant().truncatedTo(ChronoUnit.MICROS),
                descriptor.producer(),
                correlationId,
                causationId,
                payload);
    }

    /** A new time-ordered UUID (version 7, RFC 9562). */
    public static UUID newEventId() {
        return UuidCreator.getTimeOrderedEpoch();
    }
}
