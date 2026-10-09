package com.altronixsoft.opp.payment.application;

import java.util.UUID;

/** The refund changed between loading and saving (optimistic locking). */
public class RefundConcurrentlyModifiedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID refundId;

    public RefundConcurrentlyModifiedException(UUID refundId, Throwable cause) {
        super("Refund " + refundId + " was modified concurrently", cause);
        this.refundId = refundId;
    }

    public RefundConcurrentlyModifiedException(UUID refundId) {
        this(refundId, null);
    }

    public UUID refundId() {
        return refundId;
    }
}
