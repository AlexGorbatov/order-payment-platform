package com.altronixsoft.opp.payment.application;

import static com.altronixsoft.opp.payment.domain.PaymentFixtures.PI;
import static com.altronixsoft.opp.payment.domain.PaymentFixtures.at;
import static com.altronixsoft.opp.payment.domain.PaymentFixtures.fixedRandom;
import static com.altronixsoft.opp.payment.domain.PaymentFixtures.inStatus;
import static org.assertj.core.api.Assertions.assertThat;

import com.altronixsoft.opp.payment.domain.Payment;
import com.altronixsoft.opp.payment.domain.PaymentStatus;
import com.altronixsoft.opp.payment.domain.PaymentStatusSource;
import com.altronixsoft.opp.payment.domain.RetryPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The PaymentCancellationWorker (architecture §6.4, §7.6) against in-memory stores and a scripted gateway. */
class CancelPaymentIntentsServiceTest {

    private final InMemoryPayments payments = new InMemoryPayments();
    private final FakeTransactions transactions = new FakeTransactions().manage(payments);
    private final FakeGateway gateway = new FakeGateway(transactions);
    private final MovableClock clock = new MovableClock(at(100));
    private final CancelPaymentIntentsService service = new CancelPaymentIntentsService(
            payments,
            gateway,
            transactions,
            clock,
            fixedRandom(0),
            new WorkerSettings(
                    10,
                    Duration.ofMinutes(5),
                    new RetryPolicy(Duration.ofSeconds(10), Duration.ofMinutes(5), 3, 0),
                    Duration.ofSeconds(30)));

    static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private Payment cancelRequested(PaymentStatus status) {
        Payment payment = inStatus(status);
        payment.requestCancel(at(50));
        payment.pullDomainEvents();
        return payments.add(payment);
    }

    @Test
    void sendsTheCancellationOutsideATransactionAndRecordsOnlyThatItWasSent() {
        Payment payment = cancelRequested(PaymentStatus.REQUIRES_PAYMENT_METHOD);

        WorkBatchResult<CancellationOutcome> result = service.runBatch();

        assertThat(result.count(CancellationOutcome.CANCEL_SENT)).isEqualTo(1);
        assertThat(gateway.canceled)
                .containsExactly(
                        new CancelPaymentIntentRequest(payment.id(), PI, CancelPaymentIntentRequest.Reason.ABANDONED));
        Payment stored = payments.get(payment.id());
        assertThat(stored.status()).isEqualTo(PaymentStatus.REQUIRES_PAYMENT_METHOD);
        assertThat(stored.cancelSentAt()).isEqualTo(at(100));
        assertThat(stored.nextAttemptAt()).isNull();
        assertThat(service.runBatch().total()).isZero();
    }

    @Test
    void aPaymentRequiringAnActionIsCancelledToo() {
        cancelRequested(PaymentStatus.REQUIRES_ACTION);

        assertThat(service.runBatch().count(CancellationOutcome.CANCEL_SENT)).isEqualTo(1);
    }

    @Test
    void paymentsWithoutCancellationWorkAreNotTouched() {
        payments.add(inStatus(PaymentStatus.REQUIRES_PAYMENT_METHOD));
        payments.add(inStatus(PaymentStatus.SUCCEEDED));

        assertThat(service.runBatch().total()).isZero();
        assertThat(gateway.canceled).isEmpty();
    }

    @Test
    @DisplayName("F19: unexpected_state is final: no retry, the work is dropped")
    void tooLateIsNotRetried() {
        Payment payment = cancelRequested(PaymentStatus.REQUIRES_PAYMENT_METHOD);
        gateway.cancelFailure =
                FakeGateway.failure(GatewayErrorClass.PERMANENT, CancelPaymentIntentsService.UNEXPECTED_STATE);

        assertThat(service.runBatch().count(CancellationOutcome.TOO_LATE)).isEqualTo(1);

        Payment stored = payments.get(payment.id());
        assertThat(stored.nextAttemptAt()).isNull();
        assertThat(stored.cancelSentAt()).isNull();
        clock.advance(Duration.ofHours(1));
        assertThat(service.runBatch().total()).isZero();
        assertThat(gateway.canceled).hasSize(1);
    }

    @Test
    void anotherPermanentRefusalGivesUp() {
        cancelRequested(PaymentStatus.REQUIRES_PAYMENT_METHOD);
        gateway.cancelFailure = FakeGateway.failure(GatewayErrorClass.PERMANENT, "resource_missing");

        assertThat(service.runBatch().count(CancellationOutcome.GAVE_UP)).isEqualTo(1);
        assertThat(service.runBatch().total()).isZero();
    }

    @Test
    void aTransientFailureIsRetriedWithBackoffAndGivenUpAfterMaxAttempts() {
        Payment payment = cancelRequested(PaymentStatus.REQUIRES_PAYMENT_METHOD);
        gateway.cancelFailure = FakeGateway.failure(GatewayErrorClass.TRANSIENT, "api_connection_error");

        assertThat(service.runBatch().count(CancellationOutcome.RETRY_SCHEDULED))
                .isEqualTo(1);
        assertThat(payments.get(payment.id()).nextAttemptAt()).isEqualTo(at(110));
        assertThat(service.runBatch().total()).as("not due before the backoff").isZero();
        clock.advance(Duration.ofSeconds(10));
        assertThat(service.runBatch().count(CancellationOutcome.RETRY_SCHEDULED))
                .isEqualTo(1);
        clock.advance(Duration.ofSeconds(20));

        assertThat(service.runBatch().count(CancellationOutcome.GAVE_UP)).isEqualTo(1);
        assertThat(payments.get(payment.id()).nextAttemptAt()).isNull();
        assertThat(gateway.canceled).hasSize(3);
    }

    @Test
    void anOpenCircuitOrABrokenConfigurationDefersWithoutCountingAnAttempt() {
        Payment payment = cancelRequested(PaymentStatus.REQUIRES_PAYMENT_METHOD);
        gateway.cancelFailure = FakeGateway.circuitOpen();

        assertThat(service.runBatch().count(CancellationOutcome.DEFERRED)).isEqualTo(1);
        assertThat(payments.get(payment.id()).attempts()).isZero();
        assertThat(payments.get(payment.id()).nextAttemptAt()).isEqualTo(at(130));

        gateway.cancelFailure = FakeGateway.failure(GatewayErrorClass.CONFIG, "api_key_invalid");
        clock.advance(Duration.ofSeconds(30));
        assertThat(service.runBatch().count(CancellationOutcome.DEFERRED)).isEqualTo(1);
        assertThat(payments.get(payment.id()).attempts()).isZero();
    }

    @Test
    void aPaymentThatReachedAFinalStatusSinceTheClaimIsSkipped() {
        Payment payment = cancelRequested(PaymentStatus.REQUIRES_PAYMENT_METHOD);
        payments.beforeFirstRead = () -> {
            Payment current = payments.get(payment.id());
            current.applyStripeStatus("succeeded", at(99), PaymentStatusSource.WEBHOOK, "evt_1");
            payments.save(current);
        };

        assertThat(service.runBatch().count(CancellationOutcome.SKIPPED)).isEqualTo(1);
        assertThat(gateway.canceled).isEmpty();
    }
}
