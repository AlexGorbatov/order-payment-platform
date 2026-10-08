package com.altronixsoft.opp.order.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Something that happened to an order. Every status change registers exactly one of these in the aggregate; the
 * application layer turns them into integration events and hands them to the outbox, in the transaction that saves the
 * order. They are domain facts, not wire contracts: the mapping to the event schemas lives outside the domain.
 */
public sealed interface OrderDomainEvent {

    UUID orderId();

    /** What caused the change; its event id becomes the causation of the published event. */
    Trigger trigger();

    default Instant occurredAt() {
        return trigger().occurredAt();
    }

    /** The order was created and awaits payment. */
    record Placed(UUID orderId, String customerId, Money total, int itemCount, Trigger trigger)
            implements OrderDomainEvent {}

    /** The payment succeeded. */
    record Paid(UUID orderId, Trigger trigger) implements OrderDomainEvent {}

    /** The order was cancelled. */
    record Cancelled(UUID orderId, CancelReason reason, Trigger trigger) implements OrderDomainEvent {}

    /** A full refund of the order total was requested. */
    record RefundRequested(UUID orderId, UUID refundRequestId, Money amount, RefundReason reason, Trigger trigger)
            implements OrderDomainEvent {}

    /** The refund succeeded. */
    record Refunded(UUID orderId, Trigger trigger) implements OrderDomainEvent {}

    /** The refund failed. */
    record RefundFailed(UUID orderId, String failureReason, Trigger trigger) implements OrderDomainEvent {}

    /** The payment was disputed; the order is flagged, its status is unchanged. */
    record Disputed(UUID orderId, Trigger trigger) implements OrderDomainEvent {}
}
