package com.altronixsoft.opp.order.adapter.out.messaging;

import com.altronixsoft.opp.contracts.CancelReason;
import com.altronixsoft.opp.contracts.EventEnvelope;
import com.altronixsoft.opp.contracts.OrderCancelled;
import com.altronixsoft.opp.contracts.OrderCreated;
import com.altronixsoft.opp.contracts.OrderEvent;
import com.altronixsoft.opp.contracts.OrderRefundRequested;
import com.altronixsoft.opp.contracts.RefundReason;
import com.altronixsoft.opp.contracts.Topics;
import com.altronixsoft.opp.order.application.OrderEventPublisher;
import com.altronixsoft.opp.order.domain.OrderDomainEvent;
import com.altronixsoft.opp.platform.messaging.outbox.OutboxPublisher;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@link OrderEventPublisher} on the transactional outbox: the integration events of architecture §9.3 go to
 * {@code order.events.v1}, keyed by the order id.
 *
 * <ul>
 *   <li>{@code occurredAt} is the time of the change, not of the publication;
 *   <li>{@code causationId} is the consumed event that caused the change, or absent for an API call or a job;
 *   <li>{@code Paid}, {@code Refunded}, {@code RefundFailed} and {@code Disputed} are reactions to payment events and
 *       have no consumer, so they are not published.
 * </ul>
 */
@Component
class OutboxOrderEventPublisher implements OrderEventPublisher {

    private final OutboxPublisher outbox;

    OutboxOrderEventPublisher(OutboxPublisher outbox) {
        this.outbox = outbox;
    }

    @Override
    public void publish(List<OrderDomainEvent> events, UUID correlationId) {
        for (OrderDomainEvent event : events) {
            toContract(event)
                    .ifPresent(payload -> outbox.publish(envelope(event, payload, correlationId), Topics.ORDER_EVENTS));
        }
    }

    static EventEnvelope<OrderEvent> envelope(OrderDomainEvent event, OrderEvent payload, UUID correlationId) {
        return EventEnvelope.create(
                payload,
                correlationId,
                event.trigger().sourceEventId(),
                Clock.fixed(event.occurredAt(), ZoneOffset.UTC));
    }

    static Optional<OrderEvent> toContract(OrderDomainEvent event) {
        return switch (event) {
            case OrderDomainEvent.Placed placed ->
                Optional.of(new OrderCreated(
                        placed.orderId(),
                        placed.customerId(),
                        placed.total().amountMinor(),
                        placed.total().currencyCode(),
                        placed.itemCount()));
            case OrderDomainEvent.Cancelled cancelled ->
                Optional.of(new OrderCancelled(
                        cancelled.orderId(),
                        CancelReason.valueOf(cancelled.reason().name())));
            case OrderDomainEvent.RefundRequested refund ->
                Optional.of(new OrderRefundRequested(
                        refund.orderId(),
                        refund.refundRequestId(),
                        refund.amount().amountMinor(),
                        refund.amount().currencyCode(),
                        RefundReason.valueOf(refund.reason().name())));
            case OrderDomainEvent.Paid ignored -> Optional.empty();
            case OrderDomainEvent.Refunded ignored -> Optional.empty();
            case OrderDomainEvent.RefundFailed ignored -> Optional.empty();
            case OrderDomainEvent.Disputed ignored -> Optional.empty();
        };
    }
}
