package com.altronixsoft.opp.payment.application;

import com.altronixsoft.opp.payment.domain.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * One consumed order event, in the application's terms.
 *
 * @param eventId the id of the consumed event; recorded as the cause of what happens next
 * @param correlationId the business flow of the event
 */
public record OrderEventCommand(UUID eventId, UUID correlationId, UUID orderId, Outcome outcome) {

    public OrderEventCommand {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(outcome, "outcome");
    }

    /** What the order event says. */
    public sealed interface Outcome {

        /** {@code OrderCreated}: a payment is to be created. */
        record Created(String customerId, Money amount) implements Outcome {}

        /** {@code OrderCancelled}: the payment is to be cancelled. */
        record Cancelled(String reason) implements Outcome {}

        /** {@code OrderRefundRequested}: a full refund is to be made. */
        record RefundRequested(UUID refundRequestId, Money amount, String reason) implements Outcome {}
    }
}
