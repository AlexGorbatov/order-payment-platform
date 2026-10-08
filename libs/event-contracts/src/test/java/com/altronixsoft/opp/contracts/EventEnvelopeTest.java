package com.altronixsoft.opp.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventEnvelopeTest {

    @Test
    void createFillsEnvelopeFromTheCatalogAndThePayload() {
        UUID eventId = UUID.fromString("0199e0a0-7777-7000-8000-000000000007");
        PaymentSucceeded payload =
                new PaymentSucceeded(Samples.PAYMENT_ID, Samples.ORDER_ID, 100, "EUR", "pi_1", Samples.NOW);

        EventEnvelope<PaymentSucceeded> envelope = EventEnvelope.create(
                payload, Samples.CORRELATION_ID, Samples.CAUSATION_ID, Samples.CLOCK, () -> eventId);

        assertThat(envelope.eventId()).isEqualTo(eventId);
        assertThat(envelope.eventType()).isEqualTo(EventTypes.PAYMENT_SUCCEEDED);
        assertThat(envelope.eventVersion()).isEqualTo(EventTypes.V1);
        assertThat(envelope.aggregateType()).isEqualTo("Payment");
        assertThat(envelope.aggregateId()).isEqualTo(Samples.PAYMENT_ID);
        assertThat(envelope.partitionKey()).isEqualTo(Samples.ORDER_ID.toString());
        assertThat(envelope.occurredAt()).isEqualTo(Samples.NOW);
        assertThat(envelope.producer()).isEqualTo(Producers.PAYMENT_SERVICE);
        assertThat(envelope.correlationId()).isEqualTo(Samples.CORRELATION_ID);
        assertThat(envelope.causationId()).isEqualTo(Samples.CAUSATION_ID);
        assertThat(envelope.payload()).isSameAs(payload);
    }

    @Test
    void orderEventsAreAggregatedByOrderAndPaymentEventsByPayment() {
        EventEnvelope<OrderCancelled> order = EventEnvelope.create(
                new OrderCancelled(Samples.ORDER_ID, CancelReason.CUSTOMER),
                Samples.CORRELATION_ID,
                null,
                Samples.CLOCK);
        EventEnvelope<PaymentActionRequired> payment = EventEnvelope.create(
                new PaymentActionRequired(Samples.PAYMENT_ID, Samples.ORDER_ID),
                Samples.CORRELATION_ID,
                null,
                Samples.CLOCK);

        assertThat(order.aggregateType()).isEqualTo("Order");
        assertThat(order.aggregateId()).isEqualTo(Samples.ORDER_ID);
        assertThat(order.producer()).isEqualTo(Producers.ORDER_SERVICE);
        assertThat(order.causationId()).isNull();
        assertThat(payment.aggregateId()).isEqualTo(Samples.PAYMENT_ID);
        assertThat(payment.partitionKey()).isEqualTo(order.partitionKey());
    }

    @Test
    void occurredAtComesFromTheClockAndIsTruncatedToMicroseconds() {
        Instant instant = Instant.parse("2026-10-08T12:00:00.123456789Z");

        EventEnvelope<?> envelope = EventEnvelope.create(
                new PaymentActionRequired(Samples.PAYMENT_ID, Samples.ORDER_ID),
                Samples.CORRELATION_ID,
                null,
                Clock.fixed(instant, ZoneOffset.UTC));

        assertThat(envelope.occurredAt()).isEqualTo(Instant.parse("2026-10-08T12:00:00.123456Z"));
    }

    @Test
    void generatedEventIdsAreUuidV7AndTimeOrdered() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            ids.add(EventEnvelope.create(
                            new PaymentActionRequired(Samples.PAYMENT_ID, Samples.ORDER_ID),
                            Samples.CORRELATION_ID,
                            null,
                            Samples.CLOCK)
                    .eventId());
        }

        assertThat(ids).allSatisfy(id -> {
            assertThat(id.version()).isEqualTo(7);
            assertThat(id.variant()).isEqualTo(2);
        });
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(ids.stream().map(UUID::toString).toList()).isSortedAccordingTo(String::compareTo);
    }

    @Test
    void envelopeRejectsMissingOrInvalidFields() {
        EventEnvelope<?> valid = Samples.envelopes().getFirst();

        assertThatThrownBy(() -> new EventEnvelope<>(
                        valid.eventId(),
                        "OrderCreated",
                        0,
                        "Order",
                        valid.aggregateId(),
                        "k",
                        Samples.NOW,
                        "order-service",
                        Samples.CORRELATION_ID,
                        null,
                        valid.payload()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventVersion");
        assertThatThrownBy(() -> new EventEnvelope<>(
                        null,
                        "OrderCreated",
                        1,
                        "Order",
                        valid.aggregateId(),
                        "k",
                        Samples.NOW,
                        "order-service",
                        Samples.CORRELATION_ID,
                        null,
                        valid.payload()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("eventId");
        assertThatThrownBy(() -> EventEnvelope.create(valid.payload(), null, null, Samples.CLOCK))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("correlationId");
    }
}
