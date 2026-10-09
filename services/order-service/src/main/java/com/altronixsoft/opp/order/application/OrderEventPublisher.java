package com.altronixsoft.opp.order.application;

import com.altronixsoft.opp.order.domain.OrderDomainEvent;
import java.util.List;
import java.util.UUID;

/**
 * Port: hands the events an order registered to the outbox, in the transaction that saves the order (architecture
 * §7.1). The adapter decides which domain events are integration events; the others are not published.
 */
public interface OrderEventPublisher {

    /**
     * @param events the events of one change, in the order the aggregate registered them
     * @param correlationId the business flow the change belongs to; every published event carries it
     */
    void publish(List<OrderDomainEvent> events, UUID correlationId);
}
