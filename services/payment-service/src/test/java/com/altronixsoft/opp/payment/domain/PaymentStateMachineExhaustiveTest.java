package com.altronixsoft.opp.payment.domain;

import static com.altronixsoft.opp.payment.domain.PaymentFixtures.at;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Every sequence of up to four reports (six PaymentIntent statuses and a refund report, each at one of three
 * timestamps, starting from {@code REQUIRES_PAYMENT_METHOD}) against a model that only knows the diagram and the
 * ordering rule, plus the invariants that must hold whatever Stripe sends in whatever order.
 */
class PaymentStateMachineExhaustiveTest {

    private static final String[] STATUSES = {
        "requires_payment_method", "requires_confirmation", "requires_action", "processing", "succeeded", "canceled"
    };
    private static final int REFUND = STATUSES.length;
    private static final int EVENT_KINDS = STATUSES.length + 1;
    private static final int[] SECONDS = {1, 2, 3};
    private static final int DEPTH = 4;

    /** The rule of §8.3 and the diagram of §5.2, written out once more. */
    private static final class Model {
        PaymentStatus status = PaymentStatus.REQUIRES_PAYMENT_METHOD;
        Instant watermark = at(0);
        int applied;

        StripeOutcome report(PaymentStatus target, Instant observedAt) {
            if (observedAt.isBefore(watermark)) {
                return StripeOutcome.STALE_IGNORED;
            }
            if (target == status) {
                watermark = observedAt;
                return StripeOutcome.UNCHANGED;
            }
            if (!PaymentFixtures.DIAGRAM.contains(status + "->" + target)) {
                return StripeOutcome.STALE_IGNORED;
            }
            status = target;
            watermark = observedAt;
            applied++;
            return StripeOutcome.APPLIED;
        }
    }

    private static PaymentStatus targetOf(int kind) {
        return kind == REFUND ? PaymentStatus.REFUNDED : StripePaymentIntentStatus.toPaymentStatus(STATUSES[kind]);
    }

    private static StripeOutcome send(Payment payment, int kind, Instant at) {
        return kind == REFUND
                ? payment.markRefunded(
                        UUID.fromString("0199e0a0-7777-7000-8000-000000000007"),
                        "re_test_1",
                        at,
                        PaymentStatusSource.WEBHOOK,
                        "evt")
                : payment.applyStripeStatus(STATUSES[kind], at, PaymentStatusSource.WEBHOOK, "evt");
    }

    @Test
    void theAggregateAgreesWithTheModelOnEverySequence() {
        long[] sequences = {0};
        explore(new ArrayList<>(), sequences);

        assertThat(sequences[0]).isEqualTo(sum(EVENT_KINDS * SECONDS.length, DEPTH));
    }

    private static long sum(int branching, int depth) {
        long total = 0;
        long level = 1;
        for (int i = 0; i < depth; i++) {
            level *= branching;
            total += level;
        }
        return total;
    }

    private void explore(List<int[]> prefix, long[] count) {
        if (!prefix.isEmpty()) {
            count[0]++;
            check(prefix);
        }
        if (prefix.size() == DEPTH) {
            return;
        }
        for (int kind = 0; kind < EVENT_KINDS; kind++) {
            for (int second : SECONDS) {
                prefix.add(new int[] {kind, second});
                explore(prefix, count);
                prefix.remove(prefix.size() - 1);
            }
        }
    }

    private void check(List<int[]> sequence) {
        Payment payment = PaymentFixtures.created();
        payment.attachPaymentIntent("pi_x", at(0));
        Model model = new Model();
        int historyBefore = payment.history().size();
        Instant previousWatermark = payment.lastStripeEventAt();
        boolean sawSucceeded = false;
        boolean sawCanceled = false;

        for (int[] event : sequence) {
            Instant at = at(event[1]);
            StripeOutcome actual = send(payment, event[0], at);
            StripeOutcome expected = model.report(targetOf(event[0]), at);

            String context = describe(sequence);
            assertThat(actual).as(context).isEqualTo(expected);
            assertThat(payment.status()).as(context).isEqualTo(model.status);
            assertThat(payment.lastStripeEventAt()).as(context).isEqualTo(model.watermark);
            assertThat(payment.lastStripeEventAt())
                    .as("the watermark never goes back: " + context)
                    .isAfterOrEqualTo(previousWatermark);
            previousWatermark = payment.lastStripeEventAt();

            sawSucceeded |= payment.status() == PaymentStatus.SUCCEEDED || payment.status() == PaymentStatus.REFUNDED;
            sawCanceled |= payment.status() == PaymentStatus.CANCELED;
            if (sawCanceled) {
                assertThat(payment.status()).as("CANCELED is final: " + context).isEqualTo(PaymentStatus.CANCELED);
            }
            if (sawSucceeded) {
                assertThat(payment.status())
                        .as("after SUCCEEDED only REFUNDED can follow: " + context)
                        .isIn(PaymentStatus.SUCCEEDED, PaymentStatus.REFUNDED);
            }
        }
        assertThat(payment.history()).hasSize(historyBefore + model.applied);
        for (int i = 1; i < payment.history().size(); i++) {
            PaymentStatusChange change = payment.history().get(i);
            assertThat(change.from().canTransitionTo(change.to()))
                    .as("recorded " + change)
                    .isTrue();
            assertThat(change.from()).isEqualTo(payment.history().get(i - 1).to());
        }
    }

    private static String describe(List<int[]> sequence) {
        StringBuilder text = new StringBuilder("sequence");
        for (int[] event : sequence) {
            text.append(' ')
                    .append(event[0] == REFUND ? "refund" : STATUSES[event[0]])
                    .append('@')
                    .append(event[1]);
        }
        return text.toString();
    }
}
