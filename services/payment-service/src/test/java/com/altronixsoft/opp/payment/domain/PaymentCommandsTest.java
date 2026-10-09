package com.altronixsoft.opp.payment.domain;

import static com.altronixsoft.opp.payment.domain.PaymentFixtures.at;
import static com.altronixsoft.opp.payment.domain.PaymentFixtures.created;
import static com.altronixsoft.opp.payment.domain.PaymentFixtures.eur;
import static com.altronixsoft.opp.payment.domain.PaymentFixtures.fixedRandom;
import static com.altronixsoft.opp.payment.domain.PaymentFixtures.inStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Creation, the commands that are our own decisions, and the work-queue state. */
class PaymentCommandsTest {

    private static final RetryPolicy POLICY = new RetryPolicy(Duration.ofSeconds(2), Duration.ofMinutes(5), 3, 0.2);

    @Nested
    class Creation {

        @Test
        void startsCreatedAndDueImmediately() {
            UUID id = UUID.randomUUID();
            Payment payment = Payment.create(id, PaymentFixtures.ORDER_ID, "customer-1", eur(3097), PaymentFixtures.T0);

            assertThat(payment.id()).isEqualTo(id);
            assertThat(payment.orderId()).isEqualTo(PaymentFixtures.ORDER_ID);
            assertThat(payment.customerId()).isEqualTo("customer-1");
            assertThat(payment.amount()).isEqualTo(eur(3097));
            assertThat(payment.status()).isEqualTo(PaymentStatus.CREATED);
            assertThat(payment.stripePaymentIntentId()).isNull();
            assertThat(payment.lastStripeEventAt()).isNull();
            assertThat(payment.cancelRequested()).isFalse();
            assertThat(payment.disputed()).isFalse();
            assertThat(payment.attempts()).isZero();
            assertThat(payment.nextAttemptAt()).isEqualTo(PaymentFixtures.T0);
            assertThat(payment.isDue(PaymentFixtures.T0)).isTrue();
            assertThat(payment.createdAt()).isEqualTo(PaymentFixtures.T0);
            assertThat(payment.updatedAt()).isEqualTo(PaymentFixtures.T0);
            assertThat(payment.version()).isNull();
            assertThat(payment.history())
                    .containsExactly(new PaymentStatusChange(
                            null, PaymentStatus.CREATED, PaymentStatusSource.LOCAL, null, PaymentFixtures.T0));
        }

        @Test
        void refusesAnAmountThatIsNotPositive() {
            assertThatThrownBy(() -> Payment.create(
                            UUID.randomUUID(), PaymentFixtures.ORDER_ID, "c", eur(0), PaymentFixtures.T0))
                    .isInstanceOf(InvalidPaymentException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "  "})
        void refusesABlankCustomer(String customer) {
            assertThatThrownBy(() -> Payment.create(
                            UUID.randomUUID(), PaymentFixtures.ORDER_ID, customer, eur(1), PaymentFixtures.T0))
                    .isInstanceOf(InvalidPaymentException.class);
            assertThatThrownBy(() -> Payment.create(
                            UUID.randomUUID(), PaymentFixtures.ORDER_ID, null, eur(1), PaymentFixtures.T0))
                    .isInstanceOf(InvalidPaymentException.class);
        }

        @Test
        void refusesMissingParts() {
            assertThatThrownBy(() -> Payment.create(null, PaymentFixtures.ORDER_ID, "c", eur(1), PaymentFixtures.T0))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> Payment.create(UUID.randomUUID(), null, "c", eur(1), PaymentFixtures.T0))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() ->
                            Payment.create(UUID.randomUUID(), PaymentFixtures.ORDER_ID, "c", null, PaymentFixtures.T0))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> Payment.create(UUID.randomUUID(), PaymentFixtures.ORDER_ID, "c", eur(1), null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void moneyIsNeverNegative() {
            assertThatThrownBy(() -> Money.of(-1, "EUR")).isInstanceOf(IllegalArgumentException.class);
            assertThat(eur(5).isPositive()).isTrue();
            assertThat(eur(0).isPositive()).isFalse();
            assertThat(eur(5).currencyCode()).isEqualTo("EUR");
            assertThat(eur(5).toString()).contains("5 EUR");
        }

        @Test
        void restoreRebuildsWithoutValidatingOrRecording() {
            Payment original = inStatus(PaymentStatus.PROCESSING);

            Payment copy = Payment.restore(
                    original.id(),
                    original.orderId(),
                    original.customerId(),
                    original.amount(),
                    original.status(),
                    original.stripePaymentIntentId(),
                    original.lastStripeEventAt(),
                    "c",
                    "insufficient_funds",
                    "m",
                    true,
                    at(3),
                    true,
                    2,
                    at(4),
                    original.createdAt(),
                    at(5),
                    7L,
                    null,
                    null,
                    original.history());

            assertThat(copy.status()).isEqualTo(PaymentStatus.PROCESSING);
            assertThat(copy.version()).isEqualTo(7L);
            assertThat(copy.lastErrorCode()).isEqualTo("c");
            assertThat(copy.lastErrorMessage()).isEqualTo("m");
            assertThat(copy.cancelRequested()).isTrue();
            assertThat(copy.cancelSentAt()).isEqualTo(at(3));
            assertThat(copy.disputed()).isTrue();
            assertThat(copy.attempts()).isEqualTo(2);
            assertThat(copy.nextAttemptAt()).isEqualTo(at(4));
            assertThat(copy.updatedAt()).isEqualTo(at(5));
            assertThat(copy.history()).isEqualTo(original.history());
        }

        @Test
        void theHistoryIsACopy() {
            Payment payment = created();

            assertThatThrownBy(() -> payment.history().clear()).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    class PaymentIntentCreated {

        @Test
        void attachingMovesToRequiresPaymentMethodAndFinishesTheCreationWork() {
            Payment payment = created();
            payment.scheduleRetry(at(1), POLICY, fixedRandom(0), "api_connection_error", "timeout");

            payment.attachPaymentIntent("pi_1", at(5));

            assertThat(payment.status()).isEqualTo(PaymentStatus.REQUIRES_PAYMENT_METHOD);
            assertThat(payment.stripePaymentIntentId()).isEqualTo("pi_1");
            assertThat(payment.lastStripeEventAt())
                    .as("the watermark starts at the PaymentIntent")
                    .isEqualTo(at(5));
            assertThat(payment.nextAttemptAt()).isNull();
            assertThat(payment.attempts()).isZero();
            assertThat(payment.history().getLast())
                    .isEqualTo(new PaymentStatusChange(
                            PaymentStatus.CREATED,
                            PaymentStatus.REQUIRES_PAYMENT_METHOD,
                            PaymentStatusSource.STRIPE_API,
                            null,
                            at(5)));
        }

        @ParameterizedTest
        @EnumSource(value = PaymentStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "CREATED")
        void aPaymentThatIsNoLongerCreatedRefusesIt(PaymentStatus status) {
            Payment payment = inStatus(status);

            assertThatThrownBy(() -> payment.attachPaymentIntent("pi_2", at(50)))
                    .isInstanceOfSatisfying(IllegalPaymentTransitionException.class, e -> {
                        assertThat(e.from()).isEqualTo(status);
                        assertThat(e.paymentId()).isEqualTo(payment.id());
                        assertThat(e.action()).isNotBlank();
                    });
            assertThat(payment.status()).isEqualTo(status);
        }

        @Test
        void needsAnId() {
            assertThatThrownBy(() -> created().attachPaymentIntent(" ", at(1)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> created().attachPaymentIntent(null, at(1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class InitiationFailed {

        @Test
        void aPermanentErrorEndsTheInitiation() {
            Payment payment = created();

            payment.markInitiationFailed("parameter_invalid_empty", "x".repeat(2000), at(3));

            assertThat(payment.status()).isEqualTo(PaymentStatus.INITIATION_FAILED);
            assertThat(payment.lastErrorCode()).isEqualTo("parameter_invalid_empty");
            assertThat(payment.lastErrorMessage()).hasSize(Payment.MAX_ERROR_MESSAGE_LENGTH);
            assertThat(payment.nextAttemptAt()).isNull();
            assertThat(payment.history().getLast().source()).isEqualTo(PaymentStatusSource.LOCAL);
        }

        @ParameterizedTest
        @EnumSource(value = PaymentStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "CREATED")
        void onlyACreatedPaymentCanFailItsInitiation(PaymentStatus status) {
            Payment payment = inStatus(status);

            assertThatThrownBy(() -> payment.markInitiationFailed("c", "m", at(50)))
                    .isInstanceOf(IllegalPaymentTransitionException.class);
            assertThat(payment.status()).isEqualTo(status);
        }
    }

    @Nested
    class Cancel {

        @Test
        void beforeAPaymentIntentExistsItIsCanceledOnTheSpot() {
            Payment payment = created();

            assertThat(payment.requestCancel(at(2))).isEqualTo(CancelRequest.CANCELED_LOCALLY);

            assertThat(payment.status()).isEqualTo(PaymentStatus.CANCELED);
            assertThat(payment.nextAttemptAt()).as("no initiation any more").isNull();
            assertThat(payment.history().getLast().source()).isEqualTo(PaymentStatusSource.LOCAL);
        }

        @ParameterizedTest
        @EnumSource(
                value = PaymentStatus.class,
                names = {"REQUIRES_PAYMENT_METHOD", "REQUIRES_ACTION"})
        void afterwardsTheCancellationWorkerIsScheduled(PaymentStatus status) {
            Payment payment = inStatus(status);

            assertThat(payment.requestCancel(at(20))).isEqualTo(CancelRequest.CANCEL_SCHEDULED);

            assertThat(payment.status()).as("Stripe has to confirm first").isEqualTo(status);
            assertThat(payment.cancelRequested()).isTrue();
            assertThat(payment.cancelSentAt()).isNull();
            assertThat(payment.nextAttemptAt()).isEqualTo(at(20));
            assertThat(payment.attempts()).isZero();
            assertThat(payment.isDue(at(20))).isTrue();
        }

        @Test
        void askingTwiceChangesNothing() {
            Payment payment = withScheduledCancel();
            payment.leaseUntil(at(80));

            assertThat(payment.requestCancel(at(30))).isEqualTo(CancelRequest.ALREADY_REQUESTED);
            assertThat(payment.nextAttemptAt()).as("the lease is kept").isEqualTo(at(80));
        }

        @ParameterizedTest
        @EnumSource(
                value = PaymentStatus.class,
                names = {"PROCESSING", "SUCCEEDED", "REFUNDED", "INITIATION_FAILED"})
        void tooLateItDoesNothing(PaymentStatus status) {
            Payment payment = inStatus(status);
            int history = payment.history().size();

            assertThat(payment.requestCancel(at(50))).isEqualTo(CancelRequest.NOT_CANCELABLE);

            assertThat(payment.status()).isEqualTo(status);
            assertThat(payment.cancelRequested()).isFalse();
            assertThat(payment.history()).hasSize(history);
        }

        @Test
        void aCanceledPaymentIsAlreadyCanceled() {
            assertThat(inStatus(PaymentStatus.CANCELED).requestCancel(at(50)))
                    .isEqualTo(CancelRequest.ALREADY_CANCELED);
        }

        @Test
        void sendingTheCancellationFinishesTheWorkAndStripeConfirmsIt() {
            Payment payment = withScheduledCancel();
            payment.scheduleRetry(at(21), POLICY, fixedRandom(0), "api_connection_error", "timeout");

            payment.markCancelSent(at(25));

            assertThat(payment.cancelSentAt()).isEqualTo(at(25));
            assertThat(payment.nextAttemptAt()).isNull();
            assertThat(payment.attempts()).isZero();
            assertThat(payment.status()).isEqualTo(PaymentStatus.REQUIRES_PAYMENT_METHOD);

            assertThat(payment.applyStripeStatus("canceled", at(26), PaymentStatusSource.STRIPE_API))
                    .isEqualTo(StripeOutcome.APPLIED);
            assertThat(payment.status()).isEqualTo(PaymentStatus.CANCELED);
        }

        @Test
        void aPendingCancellationSurvivesAMoveBetweenCancelableStatuses() {
            Payment payment = withScheduledCancel();

            payment.applyStripeStatus("requires_action", at(21), PaymentStatusSource.WEBHOOK);

            assertThat(payment.cancelRequested()).isTrue();
            assertThat(payment.nextAttemptAt()).isEqualTo(at(20));
        }

        @ParameterizedTest
        @ValueSource(strings = {"processing", "succeeded", "canceled"})
        void aPendingCancellationIsDroppedWhenItCanNoLongerBeDone(String stripeStatus) {
            Payment payment = withScheduledCancel();

            payment.applyStripeStatus(stripeStatus, at(21), PaymentStatusSource.WEBHOOK);

            assertThat(payment.nextAttemptAt()).isNull();
            assertThat(payment.attempts()).isZero();
            assertThat(payment.cancelRequested())
                    .as("the request itself stays on record")
                    .isTrue();
        }

        private Payment withScheduledCancel() {
            Payment payment = inStatus(PaymentStatus.REQUIRES_PAYMENT_METHOD);
            payment.requestCancel(at(20));
            return payment;
        }
    }

    @Nested
    class WorkQueue {

        @Test
        void retryBacksOffWithJitterUntilTheAttemptsAreUsedUp() {
            Payment payment = created();

            assertThat(payment.scheduleRetry(at(100), POLICY, fixedRandom(0), "api_connection_error", "timeout 1"))
                    .isEqualTo(RetryDecision.RETRY_SCHEDULED);
            assertThat(payment.attempts()).isEqualTo(1);
            assertThat(payment.nextAttemptAt()).isEqualTo(at(102));
            assertThat(payment.lastErrorCode()).isEqualTo("api_connection_error");
            assertThat(payment.lastErrorMessage()).isEqualTo("timeout 1");
            assertThat(payment.updatedAt()).isEqualTo(at(100));

            assertThat(payment.scheduleRetry(at(200), POLICY, fixedRandom(0.5), "rate_limit", "429"))
                    .isEqualTo(RetryDecision.RETRY_SCHEDULED);
            assertThat(payment.attempts()).isEqualTo(2);
            assertThat(payment.nextAttemptAt())
                    .as("4 s minus half of the 20 % jitter")
                    .isEqualTo(at(200).plusMillis(3600));

            assertThat(payment.scheduleRetry(at(300), POLICY, fixedRandom(0), "api_error", "500"))
                    .isEqualTo(RetryDecision.EXHAUSTED);
            assertThat(payment.attempts()).isEqualTo(3);
            assertThat(payment.nextAttemptAt()).as("no more automatic attempts").isNull();
            assertThat(payment.status())
                    .as("the caller decides what the payment becomes")
                    .isEqualTo(PaymentStatus.CREATED);
        }

        @Test
        void aWorkerThatGivesUpEndsTheInitiationItself() {
            Payment payment = created();
            RetryDecision decision = RetryDecision.RETRY_SCHEDULED;
            for (int i = 0; i < 3; i++) {
                decision = payment.scheduleRetry(at(10 + i), POLICY, fixedRandom(0), "api_connection_error", "timeout");
            }
            assertThat(decision).isEqualTo(RetryDecision.EXHAUSTED);

            payment.markInitiationFailed("retries_exhausted", "gave up after 3 attempts", at(20));

            assertThat(payment.status()).isEqualTo(PaymentStatus.INITIATION_FAILED);
        }

        @Test
        void aLeasePostponesTheWork() {
            Payment payment = created();
            assertThat(payment.isDue(at(0))).isTrue();

            payment.leaseUntil(at(60));

            assertThat(payment.nextAttemptAt()).isEqualTo(at(60));
            assertThat(payment.isDue(at(59))).isFalse();
            assertThat(payment.isDue(at(60))).isTrue();
        }

        @Test
        void thereIsNothingToLeaseWithoutWork() {
            Payment payment = inStatus(PaymentStatus.REQUIRES_PAYMENT_METHOD);

            assertThat(payment.isDue(at(1000))).isFalse();
            assertThatThrownBy(() -> payment.leaseUntil(at(1000))).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void errorMessagesAreCutToTheColumnSize() {
            Payment payment = created();

            payment.recordError("card_declined", "y".repeat(5000), at(5));

            assertThat(payment.lastErrorCode()).isEqualTo("card_declined");
            assertThat(payment.lastErrorMessage()).hasSize(1024);
            payment.recordError("x", null, at(6));
            assertThat(payment.lastErrorMessage()).isNull();
        }

        @Test
        void aDisputeIsRecordedOnceInAnyStatus() {
            Payment payment = inStatus(PaymentStatus.SUCCEEDED);

            assertThat(payment.markDisputed("dp_test_1", "fraudulent", at(40))).isTrue();
            assertThat(payment.markDisputed("dp_test_1", "fraudulent", at(41))).isFalse();

            assertThat(payment.disputed()).isTrue();
            assertThat(payment.updatedAt()).as("the repeat changed nothing").isEqualTo(at(40));
            assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        }
    }
}
