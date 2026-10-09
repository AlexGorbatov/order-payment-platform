package com.altronixsoft.opp.payment.application;

import java.util.UUID;

/**
 * The payment changed between loading and saving (optimistic locking). The caller reloads and decides again: a
 * duplicate event is then a no-op, a worker's result is judged against the new state.
 */
public class PaymentConcurrentlyModifiedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID paymentId;

    public PaymentConcurrentlyModifiedException(UUID paymentId, Throwable cause) {
        super("Payment " + paymentId + " was modified concurrently", cause);
        this.paymentId = paymentId;
    }

    public PaymentConcurrentlyModifiedException(UUID paymentId) {
        this(paymentId, null);
    }

    public UUID paymentId() {
        return paymentId;
    }
}
