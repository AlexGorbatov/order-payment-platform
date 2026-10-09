package com.altronixsoft.opp.order.application;

import java.util.UUID;

/**
 * A payment event that neither a duplicate nor a race between the services explains: an unknown order, or a refund
 * outcome for an order that never requested that refund. Retrying cannot help; the event needs an operator, so the
 * Kafka adapter sends it to the dead-letter topic.
 */
public class UnexpectedPaymentEventException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID orderId;

    public UnexpectedPaymentEventException(UUID orderId, String message) {
        super(message);
        this.orderId = orderId;
    }

    public UUID orderId() {
        return orderId;
    }
}
