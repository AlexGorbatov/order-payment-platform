package com.altronixsoft.opp.order.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.altronixsoft.opp.contracts.CancelReason;
import com.altronixsoft.opp.contracts.EventEnvelope;
import com.altronixsoft.opp.contracts.OrderCancelled;
import com.altronixsoft.opp.contracts.OrderCreated;
import com.altronixsoft.opp.contracts.OrderEvent;
import com.altronixsoft.opp.contracts.OrderRefundRequested;
import com.altronixsoft.opp.contracts.RefundReason;
import com.altronixsoft.opp.order.domain.Money;
import com.altronixsoft.opp.order.domain.OrderDomainEvent;
import com.altronixsoft.opp.order.domain.Trigger;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Domain events to the wire contracts of architecture §9.3. */
class OutboxOrderEventPublisherTest {

    private static final UUID ORDER = UUID.fromString("0199e0a0-6666-7000-8000-000000000006");
    private static final UUID CORRELATION = UUID.fromString("0199e0a0-3333-7000-8000-000000000003");
    private static final Instant AT = Instant.parse("2026-10-08T12:00:00.123456Z");

    @Test
    void placedBecomesOrderCreated() {
        OrderDomainEvent placed =
                new OrderDomainEvent.Placed(ORDER, "customer-1", Money.of(3097, "EUR"), 2, Trigger.api(AT));

        assertThat(OutboxOrderEventPublisher.toContract(placed))
                .contains(new OrderCreated(ORDER, "customer-1", 3097, "EUR", 2));
    }

    @Test
    void cancelledBecomesOrderCancelledWithTheSameReason() {
        for (com.altronixsoft.opp.order.domain.CancelReason reason :
                com.altronixsoft.opp.order.domain.CancelReason.values()) {
            OrderDomainEvent cancelled = new OrderDomainEvent.Cancelled(ORDER, reason, Trigger.job(AT));

            assertThat(OutboxOrderEventPublisher.toContract(cancelled))
                    .contains(new OrderCancelled(ORDER, CancelReason.valueOf(reason.name())));
        }
    }

    @Test
    void refundRequestedBecomesOrderRefundRequested() {
        UUID refund = UUID.randomUUID();
        OrderDomainEvent requested = new OrderDomainEvent.RefundRequested(
                ORDER,
                refund,
                Money.of(3097, "EUR"),
                com.altronixsoft.opp.order.domain.RefundReason.LATE_PAYMENT_AFTER_CANCEL,
                Trigger.api(AT));

        assertThat(OutboxOrderEventPublisher.toContract(requested))
                .contains(new OrderRefundRequested(ORDER, refund, 3097, "EUR", RefundReason.LATE_PAYMENT_AFTER_CANCEL));
    }

    @Test
    void reactionsToPaymentEventsAreNotPublished() {
        Trigger trigger = Trigger.event(UUID.randomUUID(), AT);

        assertThat(OutboxOrderEventPublisher.toContract(new OrderDomainEvent.Paid(ORDER, trigger)))
                .isEmpty();
        assertThat(OutboxOrderEventPublisher.toContract(new OrderDomainEvent.Refunded(ORDER, trigger)))
                .isEmpty();
        assertThat(OutboxOrderEventPublisher.toContract(new OrderDomainEvent.RefundFailed(ORDER, "x", trigger)))
                .isEmpty();
        assertThat(OutboxOrderEventPublisher.toContract(new OrderDomainEvent.Disputed(ORDER, trigger)))
                .isEmpty();
    }

    @Test
    void theEnvelopeCarriesTheTimeOfTheChangeAndItsCause() {
        UUID consumed = UUID.randomUUID();
        OrderDomainEvent cancelled = new OrderDomainEvent.Cancelled(
                ORDER, com.altronixsoft.opp.order.domain.CancelReason.PAYMENT_CANCELED, Trigger.event(consumed, AT));
        OrderEvent payload = OutboxOrderEventPublisher.toContract(cancelled).orElseThrow();

        EventEnvelope<OrderEvent> envelope = OutboxOrderEventPublisher.envelope(cancelled, payload, CORRELATION);

        assertThat(envelope.eventType()).isEqualTo("OrderCancelled");
        assertThat(envelope.partitionKey()).isEqualTo(ORDER.toString());
        assertThat(envelope.aggregateId()).isEqualTo(ORDER);
        assertThat(envelope.occurredAt()).isEqualTo(AT);
        assertThat(envelope.correlationId()).isEqualTo(CORRELATION);
        assertThat(envelope.causationId()).isEqualTo(consumed);
        assertThat(envelope.producer()).isEqualTo("order-service");
    }

    @Test
    void anApiChangeHasNoCausingEvent() {
        OrderDomainEvent placed =
                new OrderDomainEvent.Placed(ORDER, "customer-1", Money.of(3097, "EUR"), 2, Trigger.api(AT));

        EventEnvelope<OrderEvent> envelope = OutboxOrderEventPublisher.envelope(
                placed, OutboxOrderEventPublisher.toContract(placed).orElseThrow(), CORRELATION);

        assertThat(envelope.causationId()).isNull();
    }
}
