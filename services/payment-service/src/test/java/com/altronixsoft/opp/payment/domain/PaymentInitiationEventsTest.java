package com.altronixsoft.opp.payment.domain;

import static com.altronixsoft.opp.payment.domain.PaymentFixtures.ORDER_ID;
import static com.altronixsoft.opp.payment.domain.PaymentFixtures.T0;
import static com.altronixsoft.opp.payment.domain.PaymentFixtures.at;
import static com.altronixsoft.opp.payment.domain.PaymentFixtures.eur;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The integration events a payment registers while it is initiated, and the trace ids it carries. */
class PaymentInitiationEventsTest {

    private static final UUID CORRELATION = UUID.fromString("0199e0a0-2222-7000-8000-000000000001");
    private static final UUID CAUSE = UUID.fromString("0199e0a0-3333-7000-8000-000000000001");

    private static Payment traced() {
        return Payment.create(UUID.randomUUID(), ORDER_ID, "customer-1", eur(3097), T0, CORRELATION, CAUSE);
    }

    @Test
    void aNewPaymentRemembersWhichFlowAndEventStartedIt() {
        Payment payment = traced();

        assertThat(payment.correlationId()).isEqualTo(CORRELATION);
        assertThat(payment.causedByEventId()).isEqualTo(CAUSE);
        assertThat(PaymentFixtures.created().correlationId()).isNull();
    }

    @Test
    void attachingThePaymentIntentRegistersPaymentInitiatedOnce() {
        Payment payment = traced();

        payment.attachPaymentIntent("pi_1", at(5));

        assertThat(payment.pullDomainEvents())
                .containsExactly(new PaymentDomainEvent.Initiated(payment.id(), ORDER_ID, "pi_1", at(5)));
        assertThat(payment.pullDomainEvents()).as("pulling empties the list").isEmpty();
    }

    @Test
    void failingTheInitiationRegistersPaymentInitiationFailedWithTheCode() {
        Payment payment = traced();

        payment.markInitiationFailed("parameter_invalid_integer", "bad amount", at(5));

        assertThat(payment.pullDomainEvents())
                .containsExactly(new PaymentDomainEvent.InitiationFailed(
                        payment.id(), ORDER_ID, "parameter_invalid_integer", at(5)));
    }

    @Test
    void aMissingErrorCodeIsReportedAsUnknown() {
        Payment payment = traced();

        payment.markInitiationFailed(null, null, at(5));

        assertThat(payment.pullDomainEvents())
                .singleElement()
                .isEqualTo(new PaymentDomainEvent.InitiationFailed(payment.id(), ORDER_ID, "unknown", at(5)));
    }

    @Test
    void aRefundRemembersItsTraceToo() {
        UUID paymentId = UUID.randomUUID();

        Refund refund = Refund.request(
                UUID.randomUUID(), paymentId, UUID.randomUUID(), eur(3097), "ADMIN", T0, CORRELATION, CAUSE);

        assertThat(refund.correlationId()).isEqualTo(CORRELATION);
        assertThat(refund.causedByEventId()).isEqualTo(CAUSE);
    }

    @Test
    void theTraceSurvivesRestoringFromStorage() {
        Payment payment = traced();

        Payment restored = Payment.restore(
                payment.id(),
                payment.orderId(),
                payment.customerId(),
                payment.amount(),
                payment.status(),
                null,
                null,
                null,
                null,
                false,
                null,
                false,
                0,
                payment.nextAttemptAt(),
                payment.createdAt(),
                payment.updatedAt(),
                0L,
                payment.correlationId(),
                payment.causedByEventId(),
                payment.history());

        assertThat(restored.correlationId()).isEqualTo(CORRELATION);
        assertThat(restored.causedByEventId()).isEqualTo(CAUSE);
        assertThat(restored.pullDomainEvents()).isEmpty();
    }
}
