package com.altronixsoft.opp.payment.application;

import com.altronixsoft.opp.payment.domain.PaymentDomainEvent;
import java.util.List;
import java.util.UUID;

/**
 * Port: hands the events a payment registered to the outbox, in the transaction that saves the payment (architecture
 * §7.1). The adapter turns them into the integration events of §9.3.
 */
public interface PaymentEventPublisher {

    /**
     * @param events the events of one change, in the order the aggregate registered them
     * @param correlationId the business flow they belong to; may be {@code null} for a payment without one, in which case
     *     the adapter starts a new flow
     * @param causationId the event that caused the change ({@code OrderCreated}), or {@code null}
     */
    void publish(List<PaymentDomainEvent> events, UUID correlationId, UUID causationId);
}
