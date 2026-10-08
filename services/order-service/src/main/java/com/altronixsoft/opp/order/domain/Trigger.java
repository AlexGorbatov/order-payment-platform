package com.altronixsoft.opp.order.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Who or what causes a change, and when. It goes into the status history and into the domain event, where it becomes
 * the causation of the published event.
 *
 * @param source API call, consumed event or job
 * @param sourceEventId id of the consumed event; {@code null} unless {@code source} is {@link TransitionSource#EVENT}
 * @param occurredAt when the change happened
 */
public record Trigger(TransitionSource source, UUID sourceEventId, Instant occurredAt) {

    public Trigger {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (source == TransitionSource.EVENT && sourceEventId == null) {
            throw new IllegalArgumentException("an EVENT trigger needs the id of the event");
        }
        if (source != TransitionSource.EVENT && sourceEventId != null) {
            throw new IllegalArgumentException("only an EVENT trigger carries an event id");
        }
    }

    public static Trigger api(Instant at) {
        return new Trigger(TransitionSource.API, null, at);
    }

    public static Trigger event(UUID eventId, Instant at) {
        return new Trigger(TransitionSource.EVENT, eventId, at);
    }

    public static Trigger job(Instant at) {
        return new Trigger(TransitionSource.JOB, null, at);
    }
}
