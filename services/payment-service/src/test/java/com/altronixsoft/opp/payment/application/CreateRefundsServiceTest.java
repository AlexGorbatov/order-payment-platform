package com.altronixsoft.opp.payment.application;

import static com.altronixsoft.opp.payment.domain.PaymentFixtures.PI;
import static com.altronixsoft.opp.payment.domain.PaymentFixtures.at;
import static com.altronixsoft.opp.payment.domain.PaymentFixtures.fixedRandom;
import static com.altronixsoft.opp.payment.domain.PaymentFixtures.inStatus;
import static org.assertj.core.api.Assertions.assertThat;

import com.altronixsoft.opp.payment.domain.Payment;
import com.altronixsoft.opp.payment.domain.PaymentDomainEvent;
import com.altronixsoft.opp.payment.domain.PaymentStatus;
import com.altronixsoft.opp.payment.domain.Refund;
import com.altronixsoft.opp.payment.domain.RefundStatus;
import com.altronixsoft.opp.payment.domain.RetryPolicy;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The RefundWorker (architecture §6.5, §7.6) against in-memory stores and a scripted gateway. */
class CreateRefundsServiceTest {

    private static final UUID CORRELATION = UUID.fromString("0199e0a0-2222-7000-8000-000000000009");
    private static final UUID CAUSE = UUID.fromString("0199e0a0-3333-7000-8000-000000000009");

    private final InMemoryPayments payments = new InMemoryPayments();
    private final InMemoryRefunds refunds = new InMemoryRefunds();
    private final RecordingEvents events = new RecordingEvents();
    private final FakeTransactions transactions = new FakeTransactions().manage(payments);
    private final FakeGateway gateway = new FakeGateway(transactions);
    private final CancelPaymentIntentsServiceTest.MovableClock clock =
            new CancelPaymentIntentsServiceTest.MovableClock(at(100));
    private final CreateRefundsService service = new CreateRefundsService(
            refunds,
            payments,
            gateway,
            events,
            transactions,
            clock,
            fixedRandom(0),
            new WorkerSettings(
                    10,
                    Duration.ofMinutes(5),
                    new RetryPolicy(Duration.ofSeconds(10), Duration.ofMinutes(5), 3, 0),
                    Duration.ofSeconds(30)));

    private Payment payment(PaymentStatus status) {
        Payment payment = inStatus(status);
        payment.pullDomainEvents();
        return payments.add(payment);
    }

    private Refund requested(Payment payment) {
        return refunds.save(Refund.request(
                UUID.randomUUID(),
                payment.id(),
                UUID.randomUUID(),
                payment.amount(),
                "ADMIN",
                at(90),
                CORRELATION,
                CAUSE));
    }

    @Test
    void createsTheRefundOutsideATransactionAndRecordsItAsPending() {
        Payment payment = payment(PaymentStatus.SUCCEEDED);
        Refund refund = requested(payment);
        gateway.thenRefund(FakeGateway.refund("re_1", "pending", null));

        assertThat(service.runBatch().count(RefundOutcome.CREATED_AT_STRIPE)).isEqualTo(1);

        assertThat(gateway.refunded)
                .containsExactly(
                        new CreateRefundRequest(refund.id(), payment.id(), payment.orderId(), PI, payment.amount()));
        assertThat(gateway.refundedInsideTransaction).containsExactly(false);
        Refund stored = refunds.findById(refund.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(RefundStatus.PENDING);
        assertThat(stored.stripeRefundId()).isEqualTo("re_1");
        assertThat(events.published).as("the outcome comes as a webhook").isEmpty();
        assertThat(service.runBatch().total()).isZero();
    }

    @Test
    void aRefundStripeAnswersAsSucceededStaysPendingUntilTheWebhook() {
        Refund refund = requested(payment(PaymentStatus.SUCCEEDED));
        gateway.thenRefund(FakeGateway.refund("re_2", "succeeded", null));

        service.runBatch();

        assertThat(refunds.findById(refund.id()).orElseThrow().status()).isEqualTo(RefundStatus.PENDING);
    }

    @Test
    @DisplayName("F20: a refund Stripe refuses fails with PaymentRefundFailed")
    void aPermanentRefusalFailsTheRefundVisibly() {
        Payment payment = payment(PaymentStatus.SUCCEEDED);
        Refund refund = requested(payment);
        gateway.thenRefuseRefund(FakeGateway.failure(GatewayErrorClass.PERMANENT, "charge_already_refunded"));

        assertThat(service.runBatch().count(RefundOutcome.FAILED)).isEqualTo(1);

        Refund stored = refunds.findById(refund.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(RefundStatus.FAILED);
        assertThat(stored.failureReason()).isEqualTo("charge_already_refunded");
        assertThat(payments.get(payment.id()).status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(events.published).singleElement().satisfies(published -> {
            assertThat(published.event())
                    .isEqualTo(new PaymentDomainEvent.RefundFailed(
                            payment.id(),
                            payment.orderId(),
                            refund.refundRequestId(),
                            "charge_already_refunded",
                            at(100)));
            assertThat(published.correlationId()).isEqualTo(CORRELATION);
            assertThat(published.causationId()).isEqualTo(CAUSE);
        });
    }

    @Test
    void aRefundStripeCreatesAlreadyFailedFails() {
        Refund refund = requested(payment(PaymentStatus.SUCCEEDED));
        gateway.thenRefund(FakeGateway.refund("re_3", "failed", "lost_or_stolen_card"));

        assertThat(service.runBatch().count(RefundOutcome.FAILED)).isEqualTo(1);
        assertThat(refunds.findById(refund.id()).orElseThrow().failureReason()).isEqualTo("lost_or_stolen_card");
    }

    @Test
    void aRefundForAPaymentThatIsNoLongerPaidFailsWithoutCallingStripe() {
        Refund refund = requested(payment(PaymentStatus.REFUNDED));

        assertThat(service.runBatch().count(RefundOutcome.FAILED)).isEqualTo(1);

        assertThat(gateway.refunded).isEmpty();
        assertThat(refunds.findById(refund.id()).orElseThrow().failureReason()).isEqualTo("payment_not_refundable");
        assertThat(events.published).hasSize(1);
    }

    @Test
    void aTransientFailureIsRetriedAndFailsTheRefundWhenTheRetriesAreUsedUp() {
        Refund refund = requested(payment(PaymentStatus.SUCCEEDED));
        gateway.thenRefuseRefund(FakeGateway.failure(GatewayErrorClass.TRANSIENT, "api_connection_error"));

        assertThat(service.runBatch().count(RefundOutcome.RETRY_SCHEDULED)).isEqualTo(1);
        assertThat(refunds.findById(refund.id()).orElseThrow().nextAttemptAt()).isEqualTo(at(110));
        clock.advance(Duration.ofSeconds(10));
        assertThat(service.runBatch().count(RefundOutcome.RETRY_SCHEDULED)).isEqualTo(1);
        clock.advance(Duration.ofSeconds(20));

        assertThat(service.runBatch().count(RefundOutcome.FAILED)).isEqualTo(1);
        assertThat(refunds.findById(refund.id()).orElseThrow().failureReason()).isEqualTo("retries_exhausted");
        assertThat(gateway.refunded)
                .as("every attempt uses the same refund id, hence the same idempotency key")
                .hasSize(3)
                .extracting(CreateRefundRequest::refundId)
                .containsOnly(refund.id());
    }

    @Test
    void anOpenCircuitOrABrokenConfigurationDefers() {
        Refund refund = requested(payment(PaymentStatus.SUCCEEDED));
        gateway.thenRefuseRefund(FakeGateway.circuitOpen())
                .thenRefuseRefund(FakeGateway.failure(GatewayErrorClass.CONFIG, "api_key_invalid"));

        assertThat(service.runBatch().count(RefundOutcome.DEFERRED)).isEqualTo(1);
        assertThat(refunds.findById(refund.id()).orElseThrow().nextAttemptAt()).isEqualTo(at(130));
        clock.advance(Duration.ofSeconds(30));
        assertThat(service.runBatch().count(RefundOutcome.DEFERRED)).isEqualTo(1);
        assertThat(refunds.findById(refund.id()).orElseThrow().attempts()).isZero();
    }

    @Test
    void workerSettingsMustBeSensible() {
        RetryPolicy policy = new RetryPolicy(Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 0);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new WorkerSettings(0, Duration.ofSeconds(1), policy, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new WorkerSettings(1, Duration.ZERO, policy, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new WorkerSettings(1, Duration.ofSeconds(1), policy, null))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new WorkerSettings(1, Duration.ofSeconds(1), null, Duration.ofSeconds(1)))
                .isInstanceOf(NullPointerException.class);
    }
}
