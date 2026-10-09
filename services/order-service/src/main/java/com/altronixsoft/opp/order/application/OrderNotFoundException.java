package com.altronixsoft.opp.order.application;

import java.util.UUID;

/**
 * No such order <em>for this caller</em>. An order that belongs to somebody else is reported exactly like one that does
 * not exist, so the API does not disclose which ids are taken (architecture §12).
 */
public class OrderNotFoundException extends RuntimeException {

    private final UUID orderId;

    public OrderNotFoundException(UUID orderId) {
        super("Order " + orderId + " was not found");
        this.orderId = orderId;
    }

    public UUID orderId() {
        return orderId;
    }
}
