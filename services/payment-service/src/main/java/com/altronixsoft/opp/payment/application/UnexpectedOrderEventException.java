package com.altronixsoft.opp.payment.application;

import java.util.UUID;

/**
 * An order event that the payment's state cannot explain (a refund for a payment that was never paid, a refund of the
 * wrong amount). Retrying cannot help; the Kafka adapter sends it to the dead-letter topic for an operator.
 */
public class UnexpectedOrderEventException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID orderId;

    public UnexpectedOrderEventException(UUID orderId, String message) {
        super(message);
        this.orderId = orderId;
    }

    public UUID orderId() {
        return orderId;
    }
}
