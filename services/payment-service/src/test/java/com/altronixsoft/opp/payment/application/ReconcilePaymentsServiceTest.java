package com.altronixsoft.opp.payment.application;

import static com.altronixsoft.opp.payment.domain.PaymentFixtures.PI;
import static com.altronixsoft.opp.payment.domain.PaymentFixtures.at;
import static com.altronixsoft.opp.payment.domain.PaymentFixtures.inStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.altronixsoft.opp.payment.domain.Money;
import com.altronixsoft.opp.payment.domain.Payment;
import com.altronixsoft.opp.payment.domain.PaymentDomainEvent;
import com.altronixsoft.opp.payment.domain.PaymentStatus;
import com.altronixsoft.opp.payment.domain.PaymentStatusSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Reconciliation (architecture §8.4, ADR-0010) against in-memory stores and a scripted gateway. */
class ReconcilePaymentsServiceTest {

    private final InMemoryPayments payments = new InMemoryPayments();
    private final FakeTransactions transactions = new FakeTransactions().manage(payments);
    private final FakeGateway gateway = new FakeGateway(transactions);
    private final RecordingEvents events = new RecordingEvents();
    private final List<ReconciliationSummary> recorded = new ArrayList<>();
    private final AtomicInteger permits = new AtomicInteger(Integer.MAX_VALUE);
    /** The fixtures' payments last changed at T0 + 10 s; the run happens 20 minutes later. */
    private final CancelPaymentIntentsServiceTest.MovableClock clock =
            new CancelPaymentIntentsServiceTest.MovableClock(at(1200).plusMillis(700));

    private final ReconcilePaymentsService service = new ReconcilePaymentsService(
            payments,
            gateway,
            events,
            () -> permits.getAndDecrement() > 0,
            recorded::add,
            transactions,
            clock,
            new ReconciliationSettings(Duration.ofMinutes(10), 50));

    private Payment stored(PaymentStatus status) {
        Payment payment = inStatus(status);
        payment.pullDomainEvents();
        return payments.add(payment);
    }

    private static GatewayPaymentIntent intent(String status) {
        return new GatewayPaymentIntent(PI, status, Money.of(3097, "EUR"), at(5), null, null, null, null);
    }

    private ReconciliationSummary run() {
        return service.run(ReconciliationSummary.Trigger.SCHEDULED);
    }

    @Test
    @DisplayName("F11: a lost succeeded webhook is caught up: SUCCEEDED, PaymentSucceeded, a drift")
    void aLostSuccessIsCaughtUp() {
        Payment payment = stored(PaymentStatus.REQUIRES_PAYMENT_METHOD);
        gateway.retrieve = id -> intent("succeeded");

        ReconciliationSummary summary = run();

        assertThat(summary.checked()).isEqualTo(1);
        assertThat(summary.drifts())
                .containsExactly(new ReconciliationSummary.Drift(
                        payment.id(), PaymentStatus.REQUIRES_PAYMENT_METHOD, PaymentStatus.SUCCEEDED));
        Payment stored = payments.get(payment.id());
        assertThat(stored.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(stored.history().getLast().source()).isEqualTo(PaymentStatusSource.RECONCILIATION);
        assertThat(stored.lastStripeEventAt())
                .as("observedAt is the second the request started")
                .isEqualTo(at(1200));
        assertThat(events.published).singleElement().satisfies(published -> {
            assertThat(published.event()).isInstanceOf(PaymentDomainEvent.Succeeded.class);
            assertThat(published.correlationId()).isEqualTo(stored.correlationId());
        });
        assertThat(gateway.retrievedInsideTransaction).containsExactly(false);
        assertThat(recorded).containsExactly(summary);
        assertThat(service.lastRun()).contains(summary);
    }

    @Test
    void aStatusStripeAgreesWithChangesNothing() {
        Payment payment = stored(PaymentStatus.REQUIRES_PAYMENT_METHOD);
        gateway.retrieve = id -> intent("requires_payment_method");

        ReconciliationSummary summary = run();

        assertThat(summary.unchanged()).isEqualTo(1);
        assertThat(summary.drifted()).isZero();
        assertThat(events.published).isEmpty();
        assertThat(payments.get(payment.id()).lastStripeEventAt()).isEqualTo(at(10));
        assertThat(run().checked())
                .as("a checked payment rests for the stale period")
                .isZero();
        clock.advance(Duration.ofMinutes(11));
        assertThat(run().checked()).isEqualTo(1);
    }

    @Test
    void aLostPaymentFailureIsRecordedWithItsCodes() {
        Payment payment = stored(PaymentStatus.REQUIRES_ACTION);
        gateway.retrieve = id -> new GatewayPaymentIntent(
                PI, "requires_payment_method", Money.of(3097, "EUR"), at(5), null, "card_declined", "lost_card", "m");

        run();

        Payment stored = payments.get(payment.id());
        assertThat(stored.status()).isEqualTo(PaymentStatus.REQUIRES_PAYMENT_METHOD);
        assertThat(stored.lastDeclineCode()).isEqualTo("lost_card");
        assertThat(events.published)
                .singleElement()
                .satisfies(published ->
                        assertThat(published.event()).isInstanceOf(PaymentDomainEvent.AttemptFailed.class));
    }

    @Test
    void onlyQuietUnfinishedPaymentsWithAPaymentIntentAreChecked() {
        stored(PaymentStatus.CREATED);
        stored(PaymentStatus.SUCCEEDED);
        stored(PaymentStatus.CANCELED);
        Payment fresh = inStatus(PaymentStatus.PROCESSING);
        fresh.applyStripeStatus("processing", at(1100), PaymentStatusSource.WEBHOOK, "evt_recent");
        payments.add(fresh);
        gateway.retrieve = id -> intent("succeeded");

        assertThat(run().checked()).isZero();
        assertThat(gateway.retrievedIds).isEmpty();
    }

    @Test
    @DisplayName("Stripe unavailable: the run goes on, the payment is checked again by the next run")
    void anUnavailableStripeFailsOnePaymentNotTheRun() {
        Payment payment = stored(PaymentStatus.PROCESSING);
        gateway.retrieve = id -> {
            throw FakeGateway.failure(GatewayErrorClass.TRANSIENT, "api_connection_error");
        };

        ReconciliationSummary failed = run();

        assertThat(failed.failed()).isEqualTo(1);
        assertThat(failed.checked()).isZero();
        assertThat(payments.reconciledAt).doesNotContainKey(payment.id());

        gateway.retrieve = id -> intent("succeeded");
        assertThat(run().drifted()).isEqualTo(1);
        assertThat(payments.get(payment.id()).status()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void anUnusableStatusFailsThePaymentButNotTheRun() {
        stored(PaymentStatus.REQUIRES_PAYMENT_METHOD);
        gateway.retrieve = id -> intent("requires_capture");

        ReconciliationSummary summary = run();

        assertThat(summary.failed()).isEqualTo(1);
        assertThat(events.published).isEmpty();
    }

    @Test
    @DisplayName("F21: a webhook that wins the race leaves the reconciliation nothing to do; one event")
    void aWebhookThatChangedThePaymentMeanwhileWins() {
        Payment payment = stored(PaymentStatus.REQUIRES_PAYMENT_METHOD);
        gateway.retrieve = id -> {
            // the succeeded webhook is applied while Stripe is being asked
            Payment current = payments.get(payment.id());
            current.applyStripeStatus("succeeded", at(1199), PaymentStatusSource.WEBHOOK, "evt_1");
            payments.save(current);
            return intent("succeeded");
        };

        ReconciliationSummary summary = run();

        assertThat(summary.unchanged()).isEqualTo(1);
        assertThat(summary.drifted()).isZero();
        assertThat(events.published)
                .as("the webhook's event is published by the webhook path")
                .isEmpty();
    }

    @Test
    void theRateLimitLeavesTheRestForTheNextRun() {
        stored(PaymentStatus.REQUIRES_PAYMENT_METHOD);
        stored(PaymentStatus.REQUIRES_ACTION);
        stored(PaymentStatus.PROCESSING);
        gateway.retrieve = id -> intent("succeeded");
        permits.set(1);

        ReconciliationSummary summary = run();

        assertThat(summary.checked()).isEqualTo(1);
        assertThat(summary.deferred()).isEqualTo(2);
        assertThat(payments.reconciledAt).hasSize(1);
    }

    @Test
    void settingsMustBeSensible() {
        assertThatThrownBy(() -> new ReconciliationSettings(Duration.ZERO, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReconciliationSettings(Duration.ofMinutes(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
