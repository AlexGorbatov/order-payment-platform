package com.altronixsoft.opp.payment.application;

import static com.altronixsoft.opp.payment.domain.PaymentFixtures.PI;
import static com.altronixsoft.opp.payment.domain.PaymentFixtures.T0;
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
import com.altronixsoft.opp.payment.domain.StripeWebhookEvent;
import com.altronixsoft.opp.payment.domain.WebhookEventStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The WebhookProcessor and the rules of {@link StripeNotificationHandler} (architecture §6.6, §8.3) against in-memory
 * stores. The payload of a stored event is a key into the notifications the fake parser returns.
 */
class ProcessWebhookEventsServiceTest {

    private static final RetryPolicy POLICY = new RetryPolicy(Duration.ofSeconds(10), Duration.ofMinutes(5), 3, 0);

    private final InMemoryPayments payments = new InMemoryPayments();
    private final InMemoryRefunds refunds = new InMemoryRefunds();
    private final InMemoryWebhookEvents webhookEvents = new InMemoryWebhookEvents();
    private final RecordingEvents events = new RecordingEvents();
    private final FakeTransactions transactions =
            new FakeTransactions().manage(payments).manage(webhookEvents);
    private final Map<String, StripeNotification> notifications = new HashMap<>();
    private final AtomicInteger parserFailures = new AtomicInteger();
    private final MovableClock clock = new MovableClock(at(100));

    private final WebhookPayloadParser parser = (type, payload) -> {
        if (parserFailures.getAndDecrement() > 0) {
            throw new IllegalStateException("database blip");
        }
        return notifications.get(payload);
    };

    private final ProcessWebhookEventsService service = new ProcessWebhookEventsService(
            webhookEvents,
            parser,
            new StripeNotificationHandler(payments, refunds, events, clock),
            transactions,
            clock,
            fixedRandom(0),
            new WebhookSettings(10, Duration.ofMinutes(1), POLICY));

    /** A clock a test can move. */
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

    private String receive(String type, Instant created, StripeNotification notification) {
        String eventId = "evt_" + UUID.randomUUID();
        notifications.put(eventId, notification);
        webhookEvents.insertIfAbsent(StripeWebhookEvent.receive(
                eventId, type, "2026-09-30.endive", false, created, eventId, clock.instant()));
        return eventId;
    }

    private static StripeNotification intent(String status) {
        return new StripeNotification.PaymentIntentChanged(PI, null, status, null, null);
    }

    private Payment stored(PaymentStatus status) {
        Payment payment = inStatus(status);
        payment.pullDomainEvents();
        return payments.add(payment);
    }

    private WebhookEventStatus statusOf(String eventId) {
        return webhookEvents.get(eventId).status();
    }

    @Nested
    class PaymentIntents {

        @Test
        void succeededMarksThePaymentAndPublishesPaymentSucceeded() {
            Payment payment = stored(PaymentStatus.REQUIRES_PAYMENT_METHOD);
            String eventId = receive("payment_intent.succeeded", at(50), intent("succeeded"));

            WebhookBatchResult result = service.runBatch();

            assertThat(result.count(WebhookOutcome.PROCESSED)).isEqualTo(1);
            assertThat(result.lags()).containsExactly(Duration.ZERO);
            assertThat(payments.get(payment.id()).status()).isEqualTo(PaymentStatus.SUCCEEDED);
            assertThat(payments.get(payment.id()).history().getLast().stripeEventId())
                    .isEqualTo(eventId);
            assertThat(statusOf(eventId)).isEqualTo(WebhookEventStatus.PROCESSED);
            assertThat(events.published).singleElement().satisfies(published -> {
                assertThat(published.event()).isInstanceOf(PaymentDomainEvent.Succeeded.class);
                assertThat(published.correlationId()).isEqualTo(payment.correlationId());
            });
        }

        @Test
        @DisplayName("F10: succeeded delivered before processing ends SUCCEEDED; the late processing is stale")
        void outOfOrderEventsEndInTheLatestStatus() {
            Payment payment = stored(PaymentStatus.REQUIRES_PAYMENT_METHOD);
            String succeeded = receive("payment_intent.succeeded", at(51), intent("succeeded"));
            clock.advance(Duration.ofSeconds(1));
            String processing = receive("payment_intent.processing", at(50), intent("processing"));

            WebhookBatchResult result = service.runBatch();

            assertThat(result.count(WebhookOutcome.PROCESSED)).isEqualTo(1);
            assertThat(result.count(WebhookOutcome.STALE)).isEqualTo(1);
            assertThat(payments.get(payment.id()).status()).isEqualTo(PaymentStatus.SUCCEEDED);
            assertThat(statusOf(succeeded)).isEqualTo(WebhookEventStatus.PROCESSED);
            assertThat(statusOf(processing)).isEqualTo(WebhookEventStatus.PROCESSED);
            assertThat(events.published).hasSize(1);
        }

        @Test
        @DisplayName("F10: a report older than the watermark is stale and counted, not an error")
        void anOlderReportIsStale() {
            Payment payment = stored(PaymentStatus.SUCCEEDED);
            String eventId = receive("payment_intent.processing", at(5), intent("processing"));

            WebhookBatchResult result = service.runBatch();

            assertThat(result.count(WebhookOutcome.STALE)).isEqualTo(1);
            assertThat(statusOf(eventId)).isEqualTo(WebhookEventStatus.PROCESSED);
            assertThat(payments.get(payment.id()).status()).isEqualTo(PaymentStatus.SUCCEEDED);
            assertThat(events.published).isEmpty();
        }

        @Test
        @DisplayName("§6.2: a failed attempt keeps its codes and publishes PaymentAttemptFailed")
        void aFailedAttemptIsRecorded() {
            Payment payment = stored(PaymentStatus.REQUIRES_PAYMENT_METHOD);
            receive(
                    "payment_intent.payment_failed",
                    at(50),
                    new StripeNotification.PaymentIntentChanged(
                            PI,
                            null,
                            "requires_payment_method",
                            null,
                            new StripeNotification.PaymentError("card_declined", "insufficient_funds", "msg")));

            WebhookBatchResult result = service.runBatch();

            assertThat(result.count(WebhookOutcome.PROCESSED)).isEqualTo(1);
            Payment stored = payments.get(payment.id());
            assertThat(stored.lastErrorCode()).isEqualTo("card_declined");
            assertThat(stored.lastDeclineCode()).isEqualTo("insufficient_funds");
            assertThat(events.published)
                    .singleElement()
                    .satisfies(published ->
                            assertThat(published.event()).isInstanceOf(PaymentDomainEvent.AttemptFailed.class));
        }

        @Test
        void aPaymentIsFoundByItsMetadataWhenThePaymentIntentIdIsUnknown() {
            Payment payment = stored(PaymentStatus.CREATED);
            receive(
                    "payment_intent.succeeded",
                    at(50),
                    new StripeNotification.PaymentIntentChanged(
                            "pi_not_attached_yet", payment.id().toString(), "succeeded", null, null));

            WebhookBatchResult result = service.runBatch();

            assertThat(result.count(WebhookOutcome.RETRY_SCHEDULED))
                    .as("the initiation worker has not recorded the PaymentIntent yet: retry later")
                    .isEqualTo(1);
            assertThat(payments.get(payment.id()).status()).isEqualTo(PaymentStatus.CREATED);
        }

        @Test
        void anUnknownOrOrphanedPaymentIntentIsIgnored() {
            Payment payment = stored(PaymentStatus.REQUIRES_PAYMENT_METHOD);
            String unknown = receive(
                    "payment_intent.succeeded",
                    at(50),
                    new StripeNotification.PaymentIntentChanged("pi_unknown", "not-a-uuid", "succeeded", null, null));
            String orphan = receive(
                    "payment_intent.succeeded",
                    at(50),
                    new StripeNotification.PaymentIntentChanged(
                            "pi_orphan", payment.id().toString(), "succeeded", null, null));

            WebhookBatchResult result = service.runBatch();

            assertThat(result.count(WebhookOutcome.IGNORED)).isEqualTo(2);
            assertThat(statusOf(unknown)).isEqualTo(WebhookEventStatus.IGNORED);
            assertThat(statusOf(orphan)).isEqualTo(WebhookEventStatus.IGNORED);
            assertThat(payments.get(payment.id()).status()).isEqualTo(PaymentStatus.REQUIRES_PAYMENT_METHOD);
        }

        @Test
        void anUnsupportedTypeIsIgnored() {
            String eventId =
                    receive("customer.created", at(50), new StripeNotification.Unsupported("customer.created"));

            assertThat(service.runBatch().count(WebhookOutcome.IGNORED)).isEqualTo(1);
            assertThat(statusOf(eventId)).isEqualTo(WebhookEventStatus.IGNORED);
        }
    }

    @Nested
    class Refunds {

        private Refund pendingRefund(Payment payment) {
            Refund refund =
                    Refund.request(UUID.randomUUID(), payment.id(), UUID.randomUUID(), payment.amount(), "ADMIN", T0);
            refund.markPending("re_1", at(60));
            return refunds.save(refund);
        }

        @Test
        void chargeRefundedCompletesTheRefund() {
            Payment payment = stored(PaymentStatus.SUCCEEDED);
            Refund refund = pendingRefund(payment);
            receive("charge.refunded", at(70), new StripeNotification.ChargeRefunded(PI, true));

            assertThat(service.runBatch().count(WebhookOutcome.PROCESSED)).isEqualTo(1);

            assertThat(payments.get(payment.id()).status()).isEqualTo(PaymentStatus.REFUNDED);
            assertThat(refunds.findById(refund.id()).orElseThrow().status()).isEqualTo(RefundStatus.SUCCEEDED);
            assertThat(events.published)
                    .singleElement()
                    .satisfies(published -> assertThat(published.event())
                            .isEqualTo(new PaymentDomainEvent.Refunded(
                                    payment.id(),
                                    payment.orderId(),
                                    refund.refundRequestId(),
                                    "re_1",
                                    payment.amount(),
                                    at(70))));
        }

        @Test
        void aRefundNotYetRecordedAsPendingIsRetried() {
            Payment payment = stored(PaymentStatus.SUCCEEDED);
            refunds.save(
                    Refund.request(UUID.randomUUID(), payment.id(), UUID.randomUUID(), payment.amount(), "ADMIN", T0));
            receive("charge.refunded", at(70), new StripeNotification.ChargeRefunded(PI, true));

            assertThat(service.runBatch().count(WebhookOutcome.RETRY_SCHEDULED)).isEqualTo(1);
            assertThat(payments.get(payment.id()).status()).isEqualTo(PaymentStatus.SUCCEEDED);
        }

        @Test
        void partialOrUnrequestedRefundsAreIgnored() {
            Payment payment = stored(PaymentStatus.SUCCEEDED);
            receive("charge.refunded", at(70), new StripeNotification.ChargeRefunded(PI, false));
            receive("charge.refunded", at(71), new StripeNotification.ChargeRefunded(PI, true));

            WebhookBatchResult result = service.runBatch();

            assertThat(result.count(WebhookOutcome.IGNORED)).isEqualTo(2);
            assertThat(payments.get(payment.id()).status()).isEqualTo(PaymentStatus.SUCCEEDED);
        }

        @Test
        void aRepeatedChargeRefundedIsStale() {
            stored(PaymentStatus.REFUNDED);
            receive("charge.refunded", at(70), new StripeNotification.ChargeRefunded(PI, true));

            assertThat(service.runBatch().count(WebhookOutcome.STALE)).isEqualTo(1);
            assertThat(events.published).isEmpty();
        }

        @Test
        @DisplayName("F20: a failed refund is recorded and announced; the payment keeps its money")
        void refundFailedRecordsTheFailure() {
            Payment payment = stored(PaymentStatus.SUCCEEDED);
            Refund refund = pendingRefund(payment);
            String eventId = receive(
                    "refund.failed",
                    at(70),
                    new StripeNotification.RefundFailed(
                            "re_unknown", PI, refund.id().toString(), "declined"));

            assertThat(service.runBatch().count(WebhookOutcome.PROCESSED)).isEqualTo(1);

            assertThat(statusOf(eventId)).isEqualTo(WebhookEventStatus.PROCESSED);
            Refund failed = refunds.findById(refund.id()).orElseThrow();
            assertThat(failed.status()).isEqualTo(RefundStatus.FAILED);
            assertThat(failed.failureReason()).isEqualTo("declined");
            assertThat(payments.get(payment.id()).status()).isEqualTo(PaymentStatus.SUCCEEDED);
            assertThat(events.published)
                    .singleElement()
                    .satisfies(published ->
                            assertThat(published.event()).isInstanceOf(PaymentDomainEvent.RefundFailed.class));
        }

        @Test
        void refundFailedForAKnownOutcomeIsStaleOrIgnored() {
            Payment payment = stored(PaymentStatus.SUCCEEDED);
            Refund failed = pendingRefund(payment);
            failed.markFailed("declined", at(65));
            refunds.save(failed);
            receive("refund.failed", at(70), new StripeNotification.RefundFailed("re_1", PI, null, "declined"));
            receive("refund.failed", at(70), new StripeNotification.RefundFailed("re_other", PI, null, "declined"));

            WebhookBatchResult result = service.runBatch();

            assertThat(result.count(WebhookOutcome.STALE)).isEqualTo(1);
            assertThat(result.count(WebhookOutcome.IGNORED)).isEqualTo(1);
            assertThat(events.published).isEmpty();
        }
    }

    @Test
    void aDisputeFlagsThePaymentOnce() {
        Payment payment = stored(PaymentStatus.SUCCEEDED);
        receive("charge.dispute.created", at(70), new StripeNotification.DisputeCreated("dp_1", PI, "fraudulent"));
        receive("charge.dispute.created", at(71), new StripeNotification.DisputeCreated("dp_1", PI, "fraudulent"));

        WebhookBatchResult result = service.runBatch();

        assertThat(result.count(WebhookOutcome.PROCESSED)).isEqualTo(1);
        assertThat(result.count(WebhookOutcome.STALE)).isEqualTo(1);
        assertThat(payments.get(payment.id()).disputed()).isTrue();
        assertThat(events.published).hasSize(1);
    }

    @Nested
    class Failures {

        @Test
        @DisplayName("F14: a failing event is retried with backoff and succeeds")
        void aTransientFailureIsRetried() {
            Payment payment = stored(PaymentStatus.REQUIRES_PAYMENT_METHOD);
            String eventId = receive("payment_intent.succeeded", at(50), intent("succeeded"));
            parserFailures.set(2);

            assertThat(service.runBatch().count(WebhookOutcome.RETRY_SCHEDULED)).isEqualTo(1);
            StripeWebhookEvent failed = webhookEvents.get(eventId);
            assertThat(failed.status()).isEqualTo(WebhookEventStatus.FAILED);
            assertThat(failed.attempts()).isEqualTo(1);
            assertThat(failed.nextAttemptAt()).isEqualTo(clock.instant().plusSeconds(10));
            assertThat(failed.lastError()).contains("database blip");
            assertThat(service.runBatch().total())
                    .as("not due before the backoff")
                    .isZero();

            clock.advance(Duration.ofSeconds(10));
            assertThat(service.runBatch().count(WebhookOutcome.RETRY_SCHEDULED)).isEqualTo(1);
            clock.advance(Duration.ofSeconds(20));
            assertThat(service.runBatch().count(WebhookOutcome.PROCESSED)).isEqualTo(1);

            assertThat(statusOf(eventId)).isEqualTo(WebhookEventStatus.PROCESSED);
            assertThat(payments.get(payment.id()).status()).isEqualTo(PaymentStatus.SUCCEEDED);
            assertThat(events.published).hasSize(1);
        }

        @Test
        @DisplayName("F14: an event that keeps failing becomes DEAD after maxAttempts")
        void aPermanentFailureEndsDead() {
            String eventId = receive("payment_intent.succeeded", at(50), intent("succeeded"));
            parserFailures.set(Integer.MAX_VALUE);

            WebhookBatchResult last = null;
            for (int run = 0; run < 3; run++) {
                last = service.runBatch();
                clock.advance(Duration.ofMinutes(5));
            }

            assertThat(last.count(WebhookOutcome.DEAD)).isEqualTo(1);
            assertThat(last.dead()).containsExactly(eventId);
            StripeWebhookEvent dead = webhookEvents.get(eventId);
            assertThat(dead.status()).isEqualTo(WebhookEventStatus.DEAD);
            assertThat(dead.attempts()).isEqualTo(3);
            assertThat(dead.nextAttemptAt()).isNull();
            assertThat(service.runBatch().total()).isZero();
        }

        @Test
        void aChangeThatFailsLeavesNothingBehind() {
            Payment payment = stored(PaymentStatus.REQUIRES_PAYMENT_METHOD);
            receive(
                    "payment_intent.succeeded",
                    at(50),
                    new StripeNotification.PaymentIntentChanged(PI, null, "requires_capture", null, null));

            assertThat(service.runBatch().count(WebhookOutcome.RETRY_SCHEDULED)).isEqualTo(1);

            assertThat(payments.get(payment.id()).status()).isEqualTo(PaymentStatus.REQUIRES_PAYMENT_METHOD);
            assertThat(events.published).isEmpty();
            assertThat(transactions.rolledBack).isEqualTo(1);
        }
    }

    @Test
    void settingsMustBeSensible() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new WebhookSettings(0, Duration.ofSeconds(1), POLICY))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new WebhookSettings(1, Duration.ZERO, POLICY))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new WebhookSettings(1, Duration.ofSeconds(1), null))
                .isInstanceOf(NullPointerException.class);
    }
}
