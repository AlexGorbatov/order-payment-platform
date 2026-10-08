package com.altronixsoft.opp.contracts;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.UUID;

/** Events published by order-service on {@link Topics#ORDER_EVENTS}; the aggregate is the order. */
public sealed interface OrderEvent extends DomainEvent permits OrderCreated, OrderCancelled, OrderRefundRequested {

    @Override
    @JsonIgnore
    default UUID aggregateId() {
        return orderId();
    }
}
