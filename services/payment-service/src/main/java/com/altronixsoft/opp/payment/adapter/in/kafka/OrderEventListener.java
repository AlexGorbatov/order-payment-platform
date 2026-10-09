package com.altronixsoft.opp.payment.adapter.in.kafka;

import com.altronixsoft.opp.contracts.DomainEvent;
import com.altronixsoft.opp.contracts.EventEnvelope;
import com.altronixsoft.opp.contracts.OrderCancelled;
import com.altronixsoft.opp.contracts.OrderCreated;
import com.altronixsoft.opp.contracts.OrderEvent;
import com.altronixsoft.opp.contracts.OrderRefundRequested;
import com.altronixsoft.opp.contracts.Topics;
import com.altronixsoft.opp.payment.application.ApplyOrderEventService;
import com.altronixsoft.opp.payment.application.OrderEventCommand;
import com.altronixsoft.opp.payment.application.OrderEventCommand.Outcome;
import com.altronixsoft.opp.payment.application.OrderEventResult;
import com.altronixsoft.opp.payment.application.UnexpectedOrderEventException;
import com.altronixsoft.opp.payment.domain.Money;
import com.altronixsoft.opp.platform.messaging.consumer.EventEnvelopeReader;
import com.altronixsoft.opp.platform.messaging.consumer.NonRetryableEventException;
import com.altronixsoft.opp.platform.messaging.inbox.InboxGuard;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code order.events.v1} as consumer group {@code payment-service} (architecture §9.1). Translation only: the
 * rules are in {@link ApplyOrderEventService}, which runs once per event id inside the inbox transaction and never calls
 * Stripe (ADR-0008).
 *
 * <ul>
 *   <li>Unknown event types are skipped by the {@link EventEnvelopeReader}.
 *   <li>An event the payment's state cannot explain becomes a {@link NonRetryableEventException}: straight to the DLT.
 *   <li>Anything else that fails (database down, an optimistic-lock conflict, a cancellation that is ahead of its
 *       {@code OrderCreated}) is retried by the platform with the retry topics.
 * </ul>
 *
 * Metric {@code payment.order.events{type, outcome}}: the {@link OrderEventResult}, {@code DUPLICATE} (already processed,
 * caught by the inbox) or {@code REJECTED} (dead-lettered).
 */
@Component
class OrderEventListener {

    static final String CONSUMER_GROUP = "payment-service";
    static final String METRIC = "payment.order.events";

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);

    private final EventEnvelopeReader reader;
    private final InboxGuard inbox;
    private final ApplyOrderEventService service;
    private final MeterRegistry meters;

    OrderEventListener(
            EventEnvelopeReader reader, InboxGuard inbox, ApplyOrderEventService service, MeterRegistry meters) {
        this.reader = reader;
        this.inbox = inbox;
        this.service = service;
        this.meters = meters;
    }

    @KafkaListener(id = "order-events", topics = Topics.ORDER_EVENTS, groupId = CONSUMER_GROUP)
    void on(ConsumerRecord<String, String> record) {
        reader.read(record).ifPresent(this::handle);
    }

    private void handle(EventEnvelope<? extends DomainEvent> envelope) {
        if (!(envelope.payload() instanceof OrderEvent order)) {
            throw new NonRetryableEventException(
                    "Event " + envelope.eventId() + " of type " + envelope.eventType() + " is not an order event");
        }
        Optional<Outcome> outcome = toOutcome(order);
        if (outcome.isEmpty()) {
            log.debug("No reaction to {} {}", envelope.eventType(), envelope.eventId());
            return;
        }
        OrderEventCommand command =
                new OrderEventCommand(envelope.eventId(), envelope.correlationId(), order.orderId(), outcome.get());
        MDC.put("correlationId", envelope.correlationId().toString());
        MDC.put("orderId", order.orderId().toString());
        try {
            AtomicReference<OrderEventResult> result = new AtomicReference<>();
            boolean executed =
                    inbox.executeOnce(CONSUMER_GROUP, envelope.eventId(), () -> result.set(service.apply(command)));
            count(envelope, executed ? result.get().name() : "DUPLICATE");
        } catch (UnexpectedOrderEventException e) {
            count(envelope, "REJECTED");
            throw new NonRetryableEventException(e.getMessage(), e);
        } finally {
            MDC.remove("correlationId");
            MDC.remove("orderId");
        }
    }

    private void count(EventEnvelope<?> envelope, String outcome) {
        meters.counter(METRIC, "type", envelope.eventType(), "outcome", outcome).increment();
    }

    static Optional<Outcome> toOutcome(OrderEvent event) {
        return switch (event) {
            case OrderCreated created ->
                Optional.of(
                        new Outcome.Created(created.customerId(), Money.of(created.amountMinor(), created.currency())));
            case OrderCancelled cancelled ->
                Optional.of(new Outcome.Cancelled(cancelled.reason().name()));
            case OrderRefundRequested refund ->
                Optional.of(new Outcome.RefundRequested(
                        refund.refundRequestId(),
                        Money.of(refund.amountMinor(), refund.currency()),
                        refund.reason().name()));
        };
    }
}
