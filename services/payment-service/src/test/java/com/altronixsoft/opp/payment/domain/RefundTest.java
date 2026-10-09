package com.altronixsoft.opp.payment.domain;

import static com.altronixsoft.opp.payment.domain.PaymentFixtures.at;
import static com.altronixsoft.opp.payment.domain.PaymentFixtures.eur;
import static com.altronixsoft.opp.payment.domain.PaymentFixtures.fixedRandom;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/** Architecture §5.3: {@code REQUESTED → PENDING → SUCCEEDED | FAILED}. */
class RefundTest {

    private static final RetryPolicy POLICY = new RetryPolicy(Duration.ofSeconds(2), Duration.ofMinutes(5), 2, 0.2);

    private static Refund inStatus(RefundStatus status) {
        Refund refund = PaymentFixtures.requestedRefund();
        switch (status) {
            case REQUESTED -> {}
            case PENDING -> refund.markPending("re_1", at(1));
            case SUCCEEDED -> {
                refund.markPending("re_1", at(1));
                refund.markSucceeded(at(2));
            }
            case FAILED -> {
                refund.markPending("re_1", at(1));
                refund.markFailed("expired_or_canceled_card", at(2));
            }
        }
        return refund;
    }

    static Stream<Arguments> allPairs() {
        return Arrays.stream(RefundStatus.values())
                .flatMap(from -> Arrays.stream(RefundStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allPairs")
    void everyPairIsAllowedExactlyWhenTheSpecSaysSo(RefundStatus from, RefundStatus to) {
        boolean allowed = (from == RefundStatus.REQUESTED && (to == RefundStatus.PENDING || to == RefundStatus.FAILED))
                || (from == RefundStatus.PENDING && (to == RefundStatus.SUCCEEDED || to == RefundStatus.FAILED));

        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
        assertThat(from.allowedTargets().contains(to)).isEqualTo(allowed);
        assertThat(from.isTerminal()).isEqualTo(from == RefundStatus.SUCCEEDED || from == RefundStatus.FAILED);
    }

    @Test
    void startsRequestedAndDueImmediately() {
        UUID id = UUID.randomUUID();
        UUID payment = UUID.randomUUID();
        UUID request = UUID.randomUUID();

        Refund refund = Refund.request(id, payment, request, eur(3097), "ADMIN", PaymentFixtures.T0);

        assertThat(refund.id()).isEqualTo(id);
        assertThat(refund.paymentId()).isEqualTo(payment);
        assertThat(refund.refundRequestId()).isEqualTo(request);
        assertThat(refund.amount()).isEqualTo(eur(3097));
        assertThat(refund.reason()).isEqualTo("ADMIN");
        assertThat(refund.status()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(refund.stripeRefundId()).isNull();
        assertThat(refund.attempts()).isZero();
        assertThat(refund.nextAttemptAt()).isEqualTo(PaymentFixtures.T0);
        assertThat(refund.isDue(PaymentFixtures.T0)).isTrue();
        assertThat(refund.createdAt()).isEqualTo(PaymentFixtures.T0);
        assertThat(refund.updatedAt()).isEqualTo(PaymentFixtures.T0);
        assertThat(refund.version()).isNull();
    }

    @Test
    void refusesWhatCannotBeRefunded() {
        UUID a = UUID.randomUUID();
        assertThatThrownBy(() -> Refund.request(a, a, a, eur(0), "ADMIN", PaymentFixtures.T0))
                .isInstanceOf(InvalidPaymentException.class);
        assertThatThrownBy(() -> Refund.request(a, a, a, eur(1), " ", PaymentFixtures.T0))
                .isInstanceOf(InvalidPaymentException.class);
        assertThatThrownBy(() -> Refund.request(a, a, a, eur(1), null, PaymentFixtures.T0))
                .isInstanceOf(InvalidPaymentException.class);
        assertThatThrownBy(() -> Refund.request(null, a, a, eur(1), "ADMIN", PaymentFixtures.T0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theHappyPath() {
        Refund refund = PaymentFixtures.requestedRefund();

        refund.markPending("re_1", at(1));
        assertThat(refund.status()).isEqualTo(RefundStatus.PENDING);
        assertThat(refund.stripeRefundId()).isEqualTo("re_1");
        assertThat(refund.nextAttemptAt()).as("the creation work is done").isNull();

        refund.markSucceeded(at(2));
        assertThat(refund.status()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(refund.updatedAt()).isEqualTo(at(2));
    }

    @Test
    void aRefundCanFailAtCreationOrLater() {
        Refund atCreation = inStatus(RefundStatus.REQUESTED);
        atCreation.markFailed("x".repeat(3000), at(1));
        assertThat(atCreation.status()).isEqualTo(RefundStatus.FAILED);
        assertThat(atCreation.failureReason()).hasSize(1024);
        assertThat(atCreation.nextAttemptAt()).isNull();

        Refund later = inStatus(RefundStatus.PENDING);
        later.markFailed("charge_for_pending_refund_disputed", at(3));
        assertThat(later.status()).isEqualTo(RefundStatus.FAILED);
        assertThat(later.failureReason()).isEqualTo("charge_for_pending_refund_disputed");
        assertThatThrownBy(() -> later.leaseUntil(at(1))).isInstanceOf(IllegalStateException.class); // no work
    }

    @ParameterizedTest
    @EnumSource(RefundStatus.class)
    void commandsThatDoNotFitTheStatusAreRefused(RefundStatus from) {
        Refund refund = inStatus(from);
        if (!from.canTransitionTo(RefundStatus.PENDING)) {
            assertThatThrownBy(() -> refund.markPending("re_2", at(9)))
                    .isInstanceOfSatisfying(IllegalRefundTransitionException.class, e -> {
                        assertThat(e.from()).isEqualTo(from);
                        assertThat(e.refundId()).isEqualTo(refund.id());
                        assertThat(e.action()).isNotBlank();
                    });
        }
        if (!from.canTransitionTo(RefundStatus.SUCCEEDED)) {
            assertThatThrownBy(() -> refund.markSucceeded(at(9))).isInstanceOf(IllegalRefundTransitionException.class);
        }
        if (!from.canTransitionTo(RefundStatus.FAILED)) {
            assertThatThrownBy(() -> refund.markFailed("late", at(9)))
                    .isInstanceOf(IllegalRefundTransitionException.class);
        }
        assertThat(refund.status()).isEqualTo(from);
    }

    @Test
    void markPendingNeedsTheStripeId() {
        assertThatThrownBy(() -> inStatus(RefundStatus.REQUESTED).markPending(" ", at(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void retryWorksLikeThePaymentsAndOnlyWhileRequested() {
        Refund refund = inStatus(RefundStatus.REQUESTED);

        assertThat(refund.scheduleRetry(at(10), POLICY, fixedRandom(0), "api_connection_error"))
                .isEqualTo(RetryDecision.RETRY_SCHEDULED);
        assertThat(refund.attempts()).isEqualTo(1);
        assertThat(refund.nextAttemptAt()).isEqualTo(at(12));
        assertThat(refund.failureReason()).isEqualTo("api_connection_error");

        assertThat(refund.scheduleRetry(at(20), POLICY, fixedRandom(0), "api_connection_error"))
                .isEqualTo(RetryDecision.EXHAUSTED);
        assertThat(refund.nextAttemptAt()).isNull();

        assertThatThrownBy(() -> inStatus(RefundStatus.PENDING).scheduleRetry(at(1), POLICY, fixedRandom(0), "x"))
                .isInstanceOf(IllegalRefundTransitionException.class);
    }

    @Test
    void aLeasePostponesTheWork() {
        Refund refund = inStatus(RefundStatus.REQUESTED);

        refund.leaseUntil(at(60));

        assertThat(refund.isDue(at(59))).isFalse();
        assertThat(refund.isDue(at(60))).isTrue();
        assertThatThrownBy(() -> inStatus(RefundStatus.PENDING).leaseUntil(at(60)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void restoreRebuildsAStoredRefund() {
        Refund original = inStatus(RefundStatus.PENDING);

        Refund copy = Refund.restore(
                original.id(),
                original.paymentId(),
                original.refundRequestId(),
                original.amount(),
                original.reason(),
                original.status(),
                original.stripeRefundId(),
                "f",
                3,
                at(7),
                original.createdAt(),
                at(8),
                5L);

        assertThat(copy.version()).isEqualTo(5L);
        assertThat(copy.attempts()).isEqualTo(3);
        assertThat(copy.nextAttemptAt()).isEqualTo(at(7));
        assertThat(copy.failureReason()).isEqualTo("f");
        assertThat(copy.updatedAt()).isEqualTo(at(8));
    }
}
