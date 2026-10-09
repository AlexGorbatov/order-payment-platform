package com.altronixsoft.opp.payment.adapter.out.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.altronixsoft.opp.contracts.EventEnvelope;
import com.altronixsoft.opp.contracts.PaymentInitiated;
import com.altronixsoft.opp.contracts.PaymentInitiationFailed;
import com.altronixsoft.opp.contracts.Topics;
import com.altronixsoft.opp.payment.domain.PaymentDomainEvent;
import com.altronixsoft.opp.platform.messaging.outbox.OutboxPublisher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class OutboxPaymentEventPublisherTest {

    private static final UUID PAYMENT = UUID.fromString("0199e0a0-1111-7000-8000-000000000001");
    private static final UUID ORDER = UUID.fromString("0199e0a0-2222-7000-8000-000000000002");
    private static final UUID CORRELATION = UUID.fromString("0199e0a0-3333-7000-8000-000000000003");
    private static final UUID CAUSE = UUID.fromString("0199e0a0-4444-7000-8000-000000000004");
    private static final Instant AT = Instant.parse("2026-10-09T10:15:30.123456Z");

    private final OutboxPublisher outbox = Mockito.mock(OutboxPublisher.class);
    private final OutboxPaymentEventPublisher publisher = new OutboxPaymentEventPublisher(outbox);

    @Test
    void mapsTheDomainEventsToTheContractEvents() {
        assertThat(OutboxPaymentEventPublisher.toContract(new PaymentDomainEvent.Initiated(PAYMENT, ORDER, "pi_1", AT)))
                .isEqualTo(new PaymentInitiated(PAYMENT, ORDER, "pi_1"));
        assertThat(OutboxPaymentEventPublisher.toContract(
                        new PaymentDomainEvent.InitiationFailed(PAYMENT, ORDER, "card_declined", AT)))
                .isEqualTo(new PaymentInitiationFailed(PAYMENT, ORDER, "card_declined"));
    }

    @Test
    void publishesEachEventToThePaymentTopicWithTheFlowAndTheCause() {
        publisher.publish(
                List.of(
                        new PaymentDomainEvent.Initiated(PAYMENT, ORDER, "pi_1", AT),
                        new PaymentDomainEvent.InitiationFailed(PAYMENT, ORDER, "x", AT)),
                CORRELATION,
                CAUSE);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<EventEnvelope<?>> envelopes = ArgumentCaptor.forClass(EventEnvelope.class);
        Mockito.verify(outbox, Mockito.times(2)).publish(envelopes.capture(), Mockito.eq(Topics.PAYMENT_EVENTS));
        List<EventEnvelope<?>> sent = new ArrayList<>(envelopes.getAllValues());
        assertThat(sent)
                .extracting(EventEnvelope::eventType)
                .containsExactly("PaymentInitiated", "PaymentInitiationFailed");
        assertThat(sent).allSatisfy(envelope -> {
            assertThat(envelope.correlationId()).isEqualTo(CORRELATION);
            assertThat(envelope.causationId()).isEqualTo(CAUSE);
            assertThat(envelope.occurredAt()).isEqualTo(AT);
            assertThat(envelope.partitionKey()).isEqualTo(ORDER.toString());
            assertThat(envelope.producer()).isEqualTo("payment-service");
        });
    }

    @Test
    void aPaymentWithoutATraceStartsANewFlow() {
        publisher.publish(List.of(new PaymentDomainEvent.Initiated(PAYMENT, ORDER, "pi_1", AT)), null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<EventEnvelope<?>> envelope = ArgumentCaptor.forClass(EventEnvelope.class);
        Mockito.verify(outbox).publish(envelope.capture(), Mockito.eq(Topics.PAYMENT_EVENTS));
        assertThat(envelope.getValue().correlationId()).isNotNull();
        assertThat(envelope.getValue().causationId()).isNull();
    }
}
