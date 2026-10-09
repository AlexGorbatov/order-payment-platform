package com.altronixsoft.opp.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.altronixsoft.opp.payment.domain.Money;
import com.altronixsoft.opp.payment.domain.Payment;
import com.altronixsoft.opp.payment.domain.PaymentDomainEvent;
import com.altronixsoft.opp.payment.domain.PaymentStatus;
import com.altronixsoft.opp.payment.domain.RetryPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The PaymentInitiationWorker (architecture §6.1, §7.6, ADR-0008) with fakes: who is called when, and what is recorded. */
class InitiatePaymentsServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-09T12:00:00Z");
    private static final UUID ORDER = UUID.fromString("0199e0a0-1111-7000-8000-000000000001");
    private static final UUID CORRELATION = UUID.fromString("0199e0a0-2222-7000-8000-000000000002");
    private static final UUID CAUSE = UUID.fromString("0199e0a0-3333-7000-8000-000000000003");
    private static final RetryPolicy POLICY = new RetryPolicy(Duration.ofSeconds(2), Duration.ofMinutes(5), 3, 0);
    private static final InitiationSettings SETTINGS =
            new InitiationSettings(5, Duration.ofMinutes(5), Duration.ofHours(23), POLICY, Duration.ofSeconds(30));

    /** A clock the test moves. */
    private static final class TestClock extends Clock {
        Instant now = T0;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }
    }

    private final TestClock clock = new TestClock();
    private final InMemoryPayments payments = new InMemoryPayments();
    private final FakeTransactions transactions = new FakeTransactions().manage(payments);
    private final FakeGateway gateway = new FakeGateway(transactions);
    private final RecordingEvents events = new RecordingEvents();
    private final RandomGenerator noJitter = new java.util.Random(1);
    private InitiatePaymentsService service;

    @BeforeEach
    void wire() {
        service = new InitiatePaymentsService(payments, gateway, events, transactions, clock, noJitter, SETTINGS);
    }

    private Payment waiting() {
        return waitingSince(T0, ORDER);
    }

    private Payment waitingSince(Instant created, UUID orderId) {
        return payments.add(Payment.create(
                UUID.randomUUID(), orderId, "customer-1", Money.of(3097, "EUR"), created, CORRELATION, CAUSE));
    }

    // ------------------------------------------------------------------------------------------ the happy path

    @Test
    void createsThePaymentIntentAndRecordsItTogetherWithTheEvent() {
        Payment payment = waiting();
        gateway.thenReturn(FakeGateway.intent("pi_1", "requires_payment_method"));

        InitiationBatchResult result = service.runBatch();

        assertThat(result.count(InitiationOutcome.INITIATED)).isEqualTo(1);
        assertThat(gateway.created)
                .containsExactly(new CreatePaymentIntentRequest(payment.id(), ORDER, Money.of(3097, "EUR")));
        Payment stored = payments.get(payment.id());
        assertThat(stored.status()).isEqualTo(PaymentStatus.REQUIRES_PAYMENT_METHOD);
        assertThat(stored.stripePaymentIntentId()).isEqualTo("pi_1");
        assertThat(stored.nextAttemptAt()).as("the work is done").isNull();
        assertThat(stored.attempts()).isZero();
        assertThat(events.published).singleElement().satisfies(published -> {
            assertThat(published.event())
                    .isEqualTo(new PaymentDomainEvent.Initiated(
                            payment.id(), ORDER, "pi_1", Instant.parse("2026-10-09T12:00:00Z")));
            assertThat(published.correlationId()).isEqualTo(CORRELATION);
            assertThat(published.causationId()).isEqualTo(CAUSE);
        });
    }

    @Test
    void stripeIsNeverCalledInsideATransaction() {
        waiting();
        waiting();
        gateway.thenReturn(FakeGateway.intent("pi_1", "requires_payment_method"));

        service.runBatch();

        assertThat(gateway.created).hasSize(2);
        assertThat(gateway.createdInsideTransaction).containsOnly(false);
        assertThat(transactions.started)
                .as("one claim transaction and one record transaction per payment")
                .isEqualTo(3);
    }

    @Test
    void thePaymentIsLeasedBeforeStripeIsCalled() {
        Payment payment = waiting();
        Instant[] dueDuringTheCall = new Instant[1];
        gateway.duringCreate =
                () -> dueDuringTheCall[0] = payments.get(payment.id()).nextAttemptAt();
        gateway.thenReturn(FakeGateway.intent("pi_1", "requires_payment_method"));

        service.runBatch();

        assertThat(dueDuringTheCall[0])
                .as("another worker would not see the payment as due while this one is calling Stripe")
                .isEqualTo(T0.plus(SETTINGS.lease()));
    }

    @Test
    void nothingDueMeansNothingHappens() {
        waitingSince(T0.plusSeconds(60), ORDER); // created in the future: not due
        InitiationBatchResult result = service.runBatch();

        assertThat(result.total()).isZero();
        assertThat(gateway.created).isEmpty();
    }

    @Test
    void onlyCreatedPaymentsAreClaimed() {
        Payment other = waiting();
        Payment stored = payments.get(other.id());
        stored.attachPaymentIntent("pi_x", T0);
        stored.requestCancel(T0); // schedules a cancellation: due, but not for this worker

        assertThat(service.runBatch().total()).isZero();
    }

    @Test
    void aBatchIsCappedAtTheBatchSizeAndTheRestStaysDue() {
        for (int i = 0; i < 7; i++) {
            waitingSince(T0, UUID.randomUUID());
        }
        gateway.thenReturn(FakeGateway.intent("pi_1", "requires_payment_method"));

        assertThat(service.runBatch().count(InitiationOutcome.INITIATED)).isEqualTo(5);
        assertThat(service.runBatch().count(InitiationOutcome.INITIATED)).isEqualTo(2);
        assertThat(service.runBatch().total()).isZero();
    }

    @Test
    void aReplayedAnswerThatIsAlreadyFurtherAlongIsTakenOver() {
        Payment payment = waiting();
        gateway.thenReturn(FakeGateway.intent("pi_1", "processing"));
        clock.advance(Duration.ofMinutes(1));

        service.runBatch();

        assertThat(payments.get(payment.id()).status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(events.published)
                .singleElement()
                .satisfies(p -> assertThat(p.event()).isInstanceOf(PaymentDomainEvent.Initiated.class));
    }

    // ------------------------------------------------------------------------------------------ transient failures
    // (F04, F06)

    @Test
    void aTransientFailureSchedulesARetryAndTheNextRunRepeatsTheSameRequest() {
        Payment payment = waiting();
        gateway.thenThrow(FakeGateway.failure(GatewayErrorClass.TRANSIENT, "api_connection_error"))
                .thenThrow(FakeGateway.failure(GatewayErrorClass.TRANSIENT, "api_error"))
                .thenReturn(FakeGateway.intent("pi_1", "requires_payment_method"));

        assertThat(service.runBatch().count(InitiationOutcome.RETRY_SCHEDULED)).isEqualTo(1);
        Payment afterFirst = payments.get(payment.id());
        assertThat(afterFirst.status()).isEqualTo(PaymentStatus.CREATED);
        assertThat(afterFirst.attempts()).isEqualTo(1);
        assertThat(afterFirst.nextAttemptAt()).isEqualTo(T0.plusSeconds(2));
        assertThat(afterFirst.lastErrorCode()).isEqualTo("api_connection_error");

        assertThat(service.runBatch().total()).as("not due yet").isZero();

        clock.advance(Duration.ofSeconds(2));
        assertThat(service.runBatch().count(InitiationOutcome.RETRY_SCHEDULED)).isEqualTo(1);
        assertThat(payments.get(payment.id()).attempts()).isEqualTo(2);
        assertThat(payments.get(payment.id()).nextAttemptAt()).isEqualTo(clock.now.plusSeconds(4));

        clock.advance(Duration.ofSeconds(4));
        assertThat(service.runBatch().count(InitiationOutcome.INITIATED)).isEqualTo(1);

        assertThat(gateway.created).hasSize(3).containsOnly(gateway.created.getFirst());
        assertThat(events.published).hasSize(1);
        assertThat(payments.get(payment.id()).stripePaymentIntentId()).isEqualTo("pi_1");
    }

    @Test
    void whenTheAttemptsAreUsedUpTheInitiationFails() {
        Payment payment = waiting();
        gateway.thenThrow(FakeGateway.failure(GatewayErrorClass.TRANSIENT, "api_connection_error"));

        service.runBatch(); // attempt 1
        clock.advance(Duration.ofMinutes(1));
        service.runBatch(); // attempt 2
        clock.advance(Duration.ofMinutes(1));
        InitiationBatchResult last = service.runBatch(); // attempt 3 = maxAttempts

        assertThat(last.count(InitiationOutcome.FAILED_EXHAUSTED)).isEqualTo(1);
        Payment stored = payments.get(payment.id());
        assertThat(stored.status()).isEqualTo(PaymentStatus.INITIATION_FAILED);
        assertThat(events.published)
                .singleElement()
                .satisfies(p -> assertThat(p.event())
                        .isInstanceOfSatisfying(PaymentDomainEvent.InitiationFailed.class, failed -> {
                            assertThat(failed.errorCode()).isEqualTo("retries_exhausted");
                            assertThat(failed.orderId()).isEqualTo(ORDER);
                        }));
        clock.advance(Duration.ofHours(1));
        assertThat(service.runBatch().total())
                .as("a failed payment is never claimed again")
                .isZero();
    }

    @Test
    void anOpenCircuitBreakerDefersWithoutCountingAnAttempt() {
        Payment payment = waiting();
        gateway.thenThrow(FakeGateway.circuitOpen());

        InitiationBatchResult result = service.runBatch();

        assertThat(result.count(InitiationOutcome.DEFERRED_CIRCUIT_OPEN)).isEqualTo(1);
        Payment stored = payments.get(payment.id());
        assertThat(stored.attempts())
                .as("not Stripe's answer, so not an attempt")
                .isZero();
        assertThat(stored.nextAttemptAt()).isEqualTo(T0.plus(SETTINGS.deferral()));
        assertThat(stored.status()).isEqualTo(PaymentStatus.CREATED);
        assertThat(events.published).isEmpty();
    }

    @Test
    void manyCircuitOpenRunsNeverExhaustThePayment() {
        Payment payment = waiting();
        gateway.thenThrow(FakeGateway.circuitOpen());
        for (int i = 0; i < 20; i++) {
            clock.advance(SETTINGS.deferral());
            service.runBatch();
        }

        assertThat(payments.get(payment.id()).status()).isEqualTo(PaymentStatus.CREATED);
        assertThat(payments.get(payment.id()).attempts()).isZero();
    }

    // ------------------------------------------------------------------------------------------ permanent failures
    // (F07)

    @Test
    void aPermanentFailureFailsTheInitiationAndTellsTheOrderOnce() {
        Payment payment = waiting();
        gateway.thenThrow(FakeGateway.failure(GatewayErrorClass.PERMANENT, "parameter_invalid_integer"));

        InitiationBatchResult result = service.runBatch();

        assertThat(result.count(InitiationOutcome.FAILED_PERMANENT)).isEqualTo(1);
        Payment stored = payments.get(payment.id());
        assertThat(stored.status()).isEqualTo(PaymentStatus.INITIATION_FAILED);
        assertThat(stored.lastErrorCode()).isEqualTo("parameter_invalid_integer");
        assertThat(events.published).singleElement().satisfies(p -> {
            assertThat(p.event()).isInstanceOfSatisfying(PaymentDomainEvent.InitiationFailed.class, failed -> {
                assertThat(failed.errorCode()).isEqualTo("parameter_invalid_integer");
                assertThat(failed.paymentId()).isEqualTo(payment.id());
            });
            assertThat(p.correlationId()).isEqualTo(CORRELATION);
            assertThat(p.causationId()).isEqualTo(CAUSE);
        });
        assertThat(gateway.created).as("no retry").hasSize(1);
    }

    @Test
    void aPermanentFailureWithoutACodeStillGetsOne() {
        waiting();
        gateway.thenThrow(
                new PaymentGatewayException(GatewayErrorClass.PERMANENT, "no", null, null, 400, null, false, null));

        service.runBatch();

        assertThat(events.published)
                .singleElement()
                .satisfies(p -> assertThat(p.event())
                        .isInstanceOfSatisfying(
                                PaymentDomainEvent.InitiationFailed.class,
                                failed -> assertThat(failed.errorCode()).isEqualTo("stripe_error")));
    }

    // ------------------------------------------------------------------------------------------ configuration problems

    @Test
    void badCredentialsAndKeyClashesWaitWithoutFailingThePayment() {
        Payment auth = waiting();
        Payment clash = waitingSince(T0, UUID.randomUUID());
        gateway.thenThrow(FakeGateway.failure(GatewayErrorClass.CONFIG, "api_key_invalid"))
                .thenThrow(FakeGateway.failure(GatewayErrorClass.IDEMPOTENCY_MISMATCH, "idempotency_key_reused"));

        InitiationBatchResult result = service.runBatch();

        assertThat(result.count(InitiationOutcome.DEFERRED_CONFIG)).isEqualTo(2);
        for (Payment p : new Payment[] {auth, clash}) {
            Payment stored = payments.get(p.id());
            assertThat(stored.status()).isEqualTo(PaymentStatus.CREATED);
            assertThat(stored.attempts()).isZero();
            assertThat(stored.nextAttemptAt()).isEqualTo(T0.plus(SETTINGS.deferral()));
            assertThat(stored.lastErrorCode()).isIn("api_key_invalid", "idempotency_key_reused");
        }
        assertThat(events.published).isEmpty();
    }

    @Test
    void aPaymentIntentInAStateThePlatformDoesNotUseWaitsForAFix() {
        Payment payment = waiting();
        gateway.thenReturn(FakeGateway.intent("pi_1", "requires_capture"));

        assertThat(service.runBatch().count(InitiationOutcome.DEFERRED_CONFIG)).isEqualTo(1);

        Payment stored = payments.get(payment.id());
        assertThat(stored.status()).isEqualTo(PaymentStatus.CREATED);
        assertThat(stored.stripePaymentIntentId()).isNull();
        assertThat(stored.lastErrorCode()).isEqualTo("unsupported_payment_intent_status");
    }

    // ------------------------------------------------------------------------------------------ the 23 h rule (F08)

    @Test
    void aPaymentThatHasWaitedPastTheWindowIsFailedWithoutCallingStripe() {
        Payment payment = waitingSince(T0.minus(Duration.ofHours(23)).minusSeconds(1), ORDER);

        InitiationBatchResult result = service.runBatch();

        assertThat(result.count(InitiationOutcome.FAILED_WINDOW_ELAPSED)).isEqualTo(1);
        assertThat(gateway.created)
                .as("a retry with a key Stripe may have forgotten could create a second PaymentIntent")
                .isEmpty();
        assertThat(payments.get(payment.id()).status()).isEqualTo(PaymentStatus.INITIATION_FAILED);
        assertThat(events.published)
                .singleElement()
                .satisfies(p -> assertThat(p.event())
                        .isInstanceOfSatisfying(
                                PaymentDomainEvent.InitiationFailed.class,
                                failed -> assertThat(failed.errorCode()).isEqualTo("idempotency_window_elapsed")));
    }

    @Test
    void justInsideTheWindowIsStillTried() {
        waitingSince(T0.minus(Duration.ofHours(22)).minus(Duration.ofMinutes(59)), ORDER);
        gateway.thenReturn(FakeGateway.intent("pi_1", "requires_payment_method"));

        assertThat(service.runBatch().count(InitiationOutcome.INITIATED)).isEqualTo(1);
    }

    @Test
    void aPaymentThatKeepsFailingTransientlyEventuallyHitsTheWindowEvenWithAGenerousPolicy() {
        InitiationSettings generous = new InitiationSettings(
                5,
                Duration.ofMinutes(5),
                Duration.ofHours(23),
                new RetryPolicy(Duration.ofMinutes(30), Duration.ofHours(2), 1000, 0),
                Duration.ofSeconds(30));
        service = new InitiatePaymentsService(payments, gateway, events, transactions, clock, noJitter, generous);
        Payment payment = waiting();
        gateway.thenThrow(FakeGateway.failure(GatewayErrorClass.TRANSIENT, "api_connection_error"));

        InitiationOutcome last = null;
        for (int i = 0; i < 200 && payments.get(payment.id()).status() == PaymentStatus.CREATED; i++) {
            clock.advance(Duration.ofHours(1));
            InitiationBatchResult result = service.runBatch();
            if (result.count(InitiationOutcome.FAILED_WINDOW_ELAPSED) > 0) {
                last = InitiationOutcome.FAILED_WINDOW_ELAPSED;
            }
        }

        assertThat(last).isEqualTo(InitiationOutcome.FAILED_WINDOW_ELAPSED);
        assertThat(payments.get(payment.id()).status()).isEqualTo(PaymentStatus.INITIATION_FAILED);
    }

    // ------------------------------------------------------------------------------------------ races

    @Test
    void aCancellationBetweenTheClaimAndTheCallStopsBeforeStripeIsCalled() {
        Payment payment = waiting();
        payments.beforeFirstRead = () -> payments.get(payment.id()).requestCancel(T0);

        InitiationBatchResult result = service.runBatch();

        assertThat(result.count(InitiationOutcome.SKIPPED_NO_LONGER_CREATED)).isEqualTo(1);
        assertThat(gateway.created).isEmpty();
        assertThat(payments.get(payment.id()).status()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(events.published).isEmpty();
    }

    @Test
    void aCancellationWhileStripeIsBeingCalledCancelsTheOrphanedPaymentIntent() {
        Payment payment = waiting();
        gateway.duringCreate = () -> payments.get(payment.id()).requestCancel(T0);
        gateway.thenReturn(FakeGateway.intent("pi_orphan", "requires_payment_method"));

        InitiationBatchResult result = service.runBatch();

        assertThat(result.count(InitiationOutcome.SKIPPED_NO_LONGER_CREATED)).isEqualTo(1);
        Payment stored = payments.get(payment.id());
        assertThat(stored.status()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(stored.stripePaymentIntentId())
                .as("the intent is not attached to a cancelled payment")
                .isNull();
        assertThat(gateway.canceled)
                .containsExactly(new CancelPaymentIntentRequest(
                        payment.id(), "pi_orphan", CancelPaymentIntentRequest.Reason.ABANDONED));
        assertThat(events.published).isEmpty();
    }

    @Test
    void failingToCancelTheOrphanIsNotAnError() {
        Payment payment = waiting();
        gateway.duringCreate = () -> payments.get(payment.id()).requestCancel(T0);
        gateway.thenReturn(FakeGateway.intent("pi_orphan", "requires_payment_method"));
        gateway.cancelFailure = FakeGateway.failure(GatewayErrorClass.TRANSIENT, "api_connection_error");

        assertThat(service.runBatch().count(InitiationOutcome.SKIPPED_NO_LONGER_CREATED))
                .isEqualTo(1);
    }

    @Test
    void losingAnOptimisticLockRaceReloadsAndTriesAgain() {
        Payment payment = waiting();
        gateway.thenReturn(FakeGateway.intent("pi_1", "requires_payment_method"));
        // the claim's own save is the first; the next two (the record transaction) lose
        InMemoryPayments flaky = new InMemoryPayments() {
            int calls;

            @Override
            public Payment save(Payment p) {
                if (++calls == 2) { // the first save of the record phase
                    throw new PaymentConcurrentlyModifiedException(p.id());
                }
                return super.save(p);
            }
        };
        flaky.add(payment);
        service = new InitiatePaymentsService(flaky, gateway, events, transactions, clock, noJitter, SETTINGS);

        assertThat(service.runBatch().count(InitiationOutcome.INITIATED)).isEqualTo(1);
        assertThat(flaky.get(payment.id()).status()).isEqualTo(PaymentStatus.REQUIRES_PAYMENT_METHOD);
        assertThat(events.published).hasSize(1);
    }

    @Test
    void persistentConflictsAreReportedNotThrown() {
        Payment payment = waiting();
        gateway.thenReturn(FakeGateway.intent("pi_1", "requires_payment_method"));
        InMemoryPayments conflicting = new InMemoryPayments() {
            int calls;

            @Override
            public Payment save(Payment p) {
                if (++calls > 1) { // everything after the claim
                    throw new PaymentConcurrentlyModifiedException(p.id());
                }
                return super.save(p);
            }
        };
        conflicting.add(payment);
        service = new InitiatePaymentsService(conflicting, gateway, events, transactions, clock, noJitter, SETTINGS);

        assertThat(service.runBatch().count(InitiationOutcome.CONFLICT)).isEqualTo(1);
        assertThat(events.published).isEmpty();
    }

    // ------------------------------------------------------------------------------------------ isolation (F05)

    @Test
    void aFailureAfterStripeAnsweredRollsBackAndTheNextRunRepeatsTheSameRequest() {
        Payment payment = waiting();
        gateway.thenReturn(FakeGateway.intent("pi_1", "requires_payment_method"));
        events.failure = new IllegalStateException("outbox down"); // the commit never happens: F05

        InitiationBatchResult first = service.runBatch();

        assertThat(first.count(InitiationOutcome.ERROR)).isEqualTo(1);
        assertThat(transactions.rolledBack).isEqualTo(1);
        Payment afterCrash = payments.get(payment.id());
        assertThat(afterCrash.status())
                .as("nothing of the failed transaction is left")
                .isEqualTo(PaymentStatus.CREATED);
        assertThat(afterCrash.stripePaymentIntentId()).isNull();
        assertThat(afterCrash.nextAttemptAt()).as("still leased by the claim").isEqualTo(T0.plus(SETTINGS.lease()));
        assertThat(service.runBatch().total())
                .as("not claimed again while leased")
                .isZero();

        events.failure = null;
        clock.advance(SETTINGS.lease());
        assertThat(service.runBatch().count(InitiationOutcome.INITIATED)).isEqualTo(1);
        assertThat(gateway.created)
                .as("the same request again; Stripe answers it with the PaymentIntent it already made")
                .hasSize(2)
                .containsOnly(gateway.created.getFirst());
        assertThat(events.published)
                .as("one event, from the transaction that committed")
                .hasSize(1);
        assertThat(payments.get(payment.id()).stripePaymentIntentId()).isEqualTo("pi_1");
    }

    @Test
    void oneBadPaymentNeverStopsTheOthers() {
        waitingSince(T0, UUID.randomUUID());
        waitingSince(T0, UUID.randomUUID());
        waitingSince(T0, UUID.randomUUID());
        gateway.thenReturn(FakeGateway.intent("pi_1", "requires_payment_method"))
                .thenThrow(FakeGateway.failure(GatewayErrorClass.PERMANENT, "parameter_invalid_integer"))
                .thenReturn(FakeGateway.intent("pi_3", "requires_payment_method"));
        // the script's last step repeats, so make the third call distinct explicitly
        InitiationBatchResult result = service.runBatch();

        assertThat(result.total()).isEqualTo(3);
        assertThat(result.count(InitiationOutcome.INITIATED)).isEqualTo(2);
        assertThat(result.count(InitiationOutcome.FAILED_PERMANENT)).isEqualTo(1);
    }

    @Test
    void aGatewayBugIsContainedToItsPayment() {
        waitingSince(T0, UUID.randomUUID());
        waitingSince(T0, UUID.randomUUID());
        gateway.duringCreate = new Runnable() {
            int calls;

            @Override
            public void run() {
                if (++calls == 1) {
                    throw new IllegalStateException("bug");
                }
            }
        };
        gateway.thenReturn(FakeGateway.intent("pi_1", "requires_payment_method"));

        InitiationBatchResult result = service.runBatch();

        assertThat(result.count(InitiationOutcome.ERROR)).isEqualTo(1);
        assertThat(result.count(InitiationOutcome.INITIATED)).isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------ settings

    @Test
    void settingsRefuseNonsense() {
        assertThatThrownBy(() -> new InitiationSettings(
                        0, Duration.ofMinutes(1), Duration.ofHours(1), POLICY, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        new InitiationSettings(1, Duration.ZERO, Duration.ofHours(1), POLICY, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InitiationSettings(
                        1, Duration.ofMinutes(1), Duration.ofHours(-1), POLICY, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InitiationSettings(1, Duration.ofMinutes(1), Duration.ofHours(1), POLICY, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InitiationSettings(
                        1, Duration.ofMinutes(1), Duration.ofHours(1), null, Duration.ofSeconds(1)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theBatchResultCountsAndIsImmutable() {
        InitiationBatchResult empty = InitiationBatchResult.empty();

        assertThat(empty.total()).isZero();
        assertThat(empty.count(InitiationOutcome.INITIATED)).isZero();
        assertThatThrownBy(() -> empty.counts().put(InitiationOutcome.ERROR, 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
