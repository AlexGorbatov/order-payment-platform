package com.altronixsoft.opp.contracts;

import java.util.UUID;

/** An order was cancelled; payment-service cancels or refunds the related payment. */
public record OrderCancelled(UUID orderId, CancelReason reason) implements OrderEvent {

    public OrderCancelled {
        Fields.require(orderId, "orderId");
        Fields.require(reason, "reason");
    }
}
