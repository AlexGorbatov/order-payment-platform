package com.altronixsoft.opp.order.application;

import java.util.UUID;

/**
 * The order changed between loading and saving (optimistic locking). The caller reloads and retries, or reports a
 * conflict.
 */
public class OrderConcurrentlyModifiedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID orderId;

    public OrderConcurrentlyModifiedException(UUID orderId, Throwable cause) {
        super("Order " + orderId + " was modified concurrently", cause);
        this.orderId = orderId;
    }

    public OrderConcurrentlyModifiedException(UUID orderId) {
        this(orderId, null);
    }

    public UUID orderId() {
        return orderId;
    }
}
