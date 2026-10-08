package com.altronixsoft.opp.platform.messaging.outbox;

import com.altronixsoft.opp.contracts.EventEnvelope;
import com.altronixsoft.opp.contracts.OrderCreated;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/** Builds {@code OrderCreated} envelopes; {@code sequence} (stored as itemCount) lets tests verify ordering. */
public final class Events {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC);

    private Events() {}

    public static EventEnvelope<OrderCreated> orderCreated(UUID orderId, int sequence) {
        return EventEnvelope.create(
                new OrderCreated(orderId, "customer-1", 4_999, "EUR", sequence),
                UUID.randomUUID(),
                UUID.randomUUID(),
                CLOCK);
    }
}
