package com.altronixsoft.opp.order.domain;

import java.util.UUID;

/** The requested change is not allowed from the order's current status (architecture §5.1). */
public class IllegalOrderTransitionException extends OrderDomainException {

    private static final long serialVersionUID = 1L;

    private final UUID orderId;
    private final OrderStatus from;
    private final String action;

    public IllegalOrderTransitionException(UUID orderId, OrderStatus from, String action) {
        super("Order " + orderId + " is " + from + " and cannot " + action);
        this.orderId = orderId;
        this.from = from;
        this.action = action;
    }

    public UUID orderId() {
        return orderId;
    }

    public OrderStatus from() {
        return from;
    }

    public String action() {
        return action;
    }
}
