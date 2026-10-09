package com.altronixsoft.opp.payment.adapter.out.messaging;

import com.altronixsoft.opp.contracts.EventEnvelope;
import com.altronixsoft.opp.contracts.PaymentEvent;
import com.altronixsoft.opp.contracts.PaymentInitiated;
import com.altronixsoft.opp.contracts.PaymentInitiationFailed;
import com.altronixsoft.opp.contracts.Topics;
import com.altronixsoft.opp.payment.application.PaymentEventPublisher;
import com.altronixsoft.opp.payment.domain.PaymentDomainEvent;
import com.altronixsoft.opp.platform.messaging.outbox.OutboxPublisher;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@link PaymentEventPublisher} on the transactional outbox: the integration events of architecture §9.3 go to
 * {@code payment.events.v1}, keyed by the order id. {@code occurredAt} is the time of the change; {@code causationId} is the
 * consumed event that started the payment ({@code OrderCreated}).
 */
@Component
class OutboxPaymentEventPublisher implements PaymentEventPublisher {

    private final OutboxPublisher outbox;

    OutboxPaymentEventPublisher(OutboxPublisher outbox) {
        this.outbox = outbox;
    }

    @Override
    public void publish(List<PaymentDomainEvent> events, UUID correlationId, UUID causationId) {
        UUID flow = correlationId == null ? UUID.randomUUID() : correlationId;
        for (PaymentDomainEvent event : events) {
            outbox.publish(envelope(event, flow, causationId), Topics.PAYMENT_EVENTS);
        }
    }

    static EventEnvelope<PaymentEvent> envelope(PaymentDomainEvent event, UUID correlationId, UUID causationId) {
        return EventEnvelope.create(
                toContract(event), correlationId, causationId, Clock.fixed(event.occurredAt(), ZoneOffset.UTC));
    }

    static PaymentEvent toContract(PaymentDomainEvent event) {
        return switch (event) {
            case PaymentDomainEvent.Initiated initiated ->
                new PaymentInitiated(initiated.paymentId(), initiated.orderId(), initiated.stripePaymentIntentId());
            case PaymentDomainEvent.InitiationFailed failed ->
                new PaymentInitiationFailed(failed.paymentId(), failed.orderId(), failed.errorCode());
        };
    }
}
