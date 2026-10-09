package com.altronixsoft.opp.payment.application;

import static com.altronixsoft.opp.payment.domain.PaymentFixtures.T0;
import static com.altronixsoft.opp.payment.domain.PaymentFixtures.eur;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.altronixsoft.opp.payment.application.OrderEventCommand.Outcome;
import com.altronixsoft.opp.payment.domain.Payment;
import com.altronixsoft.opp.payment.domain.PaymentDomainEvent;
import com.altronixsoft.opp.payment.domain.PaymentFixtures;
import com.altronixsoft.opp.payment.domain.PaymentStatus;
import com.altronixsoft.opp.payment.domain.Refund;
import com.altronixsoft.opp.payment.domain.RefundStatus;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApplyOrderEventServiceTest {

    private static final UUID ORDER = PaymentFixtures.ORDER_ID;
    private static final UUID CORRELATION = UUID.fromString("0199e0a0-2222-7000-8000-000000000001");
    private static final UUID EVENT = UUID.fromString("0199e0a0-3333-7000-8000-000000000001");

    private final InMemoryPayments payments = new InMemoryPayments();
    private final InMemoryRefunds refunds = new InMemoryRefunds();
    private final RecordingEvents events = new RecordingEvents();
    private final AtomicInteger sequence = new AtomicInteger();
    private final IdGenerator ids =
            () -> new UUID(0x0199e0a044447000L, 0x8000000000000000L + sequence.incrementAndGet());
    private ApplyOrderEventService service;

    @BeforeEach
    void setUp() {
        service = new ApplyOrderEventService(
                payments, refunds, events, ids, Clock.fixed(T0.plusSeconds(60), ZoneOffset.UTC));
    }

    private OrderEventCommand created() {
        return new OrderEventCommand(EVENT, CORRELATION, ORDER, new Outcome.Created("customer-1", eur(3097)));
    }

    private OrderEventCommand cancelled() {
        return new OrderEventCommand(EVENT, CORRELATION, ORDER, new Outcome.Cancelled("CUSTOMER"));
    }

    private OrderEventCommand refundRequested(UUID requestId, long minor) {
        return new OrderEventCommand(
                EVENT, CORRELATION, ORDER, new Outcome.RefundRequested(requestId, eur(minor), "ADMIN"));
    }

    @Test
    void orderCreatedCreatesAPaymentThatIsDueAtOnce() {
        assertThat(service.apply(created())).isEqualTo(OrderEventResult.PAYMENT_CREATED);

        Payment payment = payments.all().getFirst();
        assertThat(payment.orderId()).isEqualTo(ORDER);
        assertThat(payment.customerId()).isEqualTo("customer-1");
        assertThat(payment.amount()).isEqualTo(eur(3097));
        assertThat(payment.status()).isEqualTo(PaymentStatus.CREATED);
        assertThat(payment.nextAttemptAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(payment.isDue(T0.plusSeconds(60))).isTrue();
        assertThat(payment.correlationId()).isEqualTo(CORRELATION);
        assertThat(payment.causedByEventId()).isEqualTo(EVENT);
    }

    @Test
    void aSecondOrderCreatedForTheSameOrderChangesNothing() {
        service.apply(created());

        assertThat(service.apply(created())).isEqualTo(OrderEventResult.PAYMENT_ALREADY_EXISTS);

        assertThat(payments.all()).hasSize(1);
        assertThat(payments.saves).isEqualTo(1);
    }

    @Test
    void cancellingBeforeStripeWasCalledCancelsLocally() {
        service.apply(created());

        assertThat(service.apply(cancelled())).isEqualTo(OrderEventResult.PAYMENT_CANCELED);

        Payment payment = payments.all().getFirst();
        assertThat(payment.status()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(events.published).singleElement().satisfies(published -> {
            assertThat(published.event())
                    .isEqualTo(new PaymentDomainEvent.Canceled(
                            payment.id(), ORDER, "canceled_before_payment_intent", T0.plusSeconds(60)));
            assertThat(published.correlationId()).isEqualTo(CORRELATION);
            assertThat(published.causationId()).isEqualTo(EVENT);
        });
    }

    @Test
    void cancellingAPayableIntentSchedulesTheCancellation() {
        payments.add(PaymentFixtures.withPaymentIntent());

        assertThat(service.apply(cancelled())).isEqualTo(OrderEventResult.CANCEL_SCHEDULED);

        Payment payment = payments.all().getFirst();
        assertThat(payment.cancelRequested()).isTrue();
        assertThat(payment.status()).isEqualTo(PaymentStatus.REQUIRES_PAYMENT_METHOD);
    }

    @Test
    void aRepeatedCancellationIsNotScheduledTwice() {
        payments.add(PaymentFixtures.withPaymentIntent());
        service.apply(cancelled());
        int saves = payments.saves;

        assertThat(service.apply(cancelled())).isEqualTo(OrderEventResult.CANCEL_ALREADY_PENDING);

        assertThat(payments.saves).isEqualTo(saves);
    }

    @Test
    void cancellingAPaymentStripeCannotCancelIsReportedAndChangesNothing() {
        payments.add(PaymentFixtures.inStatus(PaymentStatus.PROCESSING));

        assertThat(service.apply(cancelled())).isEqualTo(OrderEventResult.CANCEL_TOO_LATE);

        assertThat(payments.saves).isZero();
        assertThat(payments.all().getFirst().cancelRequested()).isFalse();
    }

    @Test
    void cancellingWithoutAPaymentIsRetryableBecauseOrderCreatedMayBeInFlight() {
        assertThatThrownBy(() -> service.apply(cancelled())).isInstanceOf(PaymentNotFoundException.class);
    }

    @Test
    void aRefundRequestCreatesARequestedRefundForTheSucceededPayment() {
        Payment payment = payments.add(PaymentFixtures.inStatus(PaymentStatus.SUCCEEDED));
        UUID requestId = UUID.randomUUID();

        assertThat(service.apply(refundRequested(requestId, 3097))).isEqualTo(OrderEventResult.REFUND_REQUESTED);

        Refund refund = refunds.stored.values().iterator().next();
        assertThat(refund.paymentId()).isEqualTo(payment.id());
        assertThat(refund.refundRequestId()).isEqualTo(requestId);
        assertThat(refund.amount()).isEqualTo(eur(3097));
        assertThat(refund.status()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(refund.correlationId()).isEqualTo(CORRELATION);
        assertThat(refund.causedByEventId()).isEqualTo(EVENT);
    }

    @Test
    void aRepeatedRefundRequestCreatesNoSecondRefund() {
        payments.add(PaymentFixtures.inStatus(PaymentStatus.SUCCEEDED));
        UUID requestId = UUID.randomUUID();
        service.apply(refundRequested(requestId, 3097));

        assertThat(service.apply(refundRequested(requestId, 3097)))
                .isEqualTo(OrderEventResult.REFUND_ALREADY_REQUESTED);

        assertThat(refunds.stored).hasSize(1);
    }

    @Test
    void aRefundForAPaymentThatWasNotPaidFailsVisibly() {
        Payment payment = payments.add(PaymentFixtures.withPaymentIntent());
        UUID requestId = UUID.randomUUID();

        assertThat(service.apply(refundRequested(requestId, 3097))).isEqualTo(OrderEventResult.REFUND_FAILED);

        Refund refund = refunds.findByRefundRequestId(requestId).orElseThrow();
        assertThat(refund.status()).isEqualTo(RefundStatus.FAILED);
        assertThat(refund.failureReason()).isEqualTo("payment_not_succeeded");
        assertThat(refund.nextAttemptAt()).as("nothing for the refund worker").isNull();
        assertThat(events.published).singleElement().satisfies(published -> {
            assertThat(published.event())
                    .isEqualTo(new PaymentDomainEvent.RefundFailed(
                            payment.id(), ORDER, requestId, "payment_not_succeeded", T0.plusSeconds(60)));
            assertThat(published.causationId()).isEqualTo(EVENT);
        });
        assertThat(service.apply(refundRequested(requestId, 3097)))
                .as("F22: the same request again changes nothing")
                .isEqualTo(OrderEventResult.REFUND_ALREADY_REQUESTED);
    }

    @Test
    void aRefundForARefundedPaymentFailsAsAlreadyRefunded() {
        payments.add(PaymentFixtures.inStatus(PaymentStatus.REFUNDED));
        UUID requestId = UUID.randomUUID();

        assertThat(service.apply(refundRequested(requestId, 3097))).isEqualTo(OrderEventResult.REFUND_FAILED);

        assertThat(refunds.findByRefundRequestId(requestId).orElseThrow().failureReason())
                .isEqualTo("already_refunded");
    }

    @Test
    void aPartialRefundIsRejectedBecauseOnlyFullRefundsExist() {
        payments.add(PaymentFixtures.inStatus(PaymentStatus.SUCCEEDED));

        assertThatThrownBy(() -> service.apply(refundRequested(UUID.randomUUID(), 1000)))
                .isInstanceOf(UnexpectedOrderEventException.class);
        assertThat(refunds.stored).isEmpty();
    }

    @Test
    void aRefundWithoutAPaymentIsRetryable() {
        assertThatThrownBy(() -> service.apply(refundRequested(UUID.randomUUID(), 3097)))
                .isInstanceOf(PaymentNotFoundException.class);
    }
}
