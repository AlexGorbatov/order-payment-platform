package com.altronixsoft.opp.order.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One entry of the order's status history (table {@code order_status_history}).
 *
 * @param from previous status; {@code null} for the entry that creates the order. Equal to {@code to} for changes that
 *     do not move the status, such as a dispute
 * @param to new status
 * @param reason why: a cancel or refund reason, a failure reason or {@code DISPUTED}; may be {@code null}
 * @param source what caused it
 * @param sourceEventId the consumed event, if any
 * @param occurredAt when it happened
 */
public record StatusChange(
        OrderStatus from,
        OrderStatus to,
        String reason,
        TransitionSource source,
        UUID sourceEventId,
        Instant occurredAt) {

    public StatusChange {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
