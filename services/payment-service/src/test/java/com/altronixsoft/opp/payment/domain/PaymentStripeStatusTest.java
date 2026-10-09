package com.altronixsoft.opp.payment.domain;

import static com.altronixsoft.opp.payment.domain.PaymentFixtures.at;
import static com.altronixsoft.opp.payment.domain.PaymentFixtures.inStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/** The ordering rule of architecture §8.3: a report is applied only if it is current and the transition is allowed. */
class PaymentStripeStatusTest {

    /** What Stripe can report as a PaymentIntent status, with the status it maps to. */
    private static final String[][] REPORTS = {
        {"requires_payment_method", "REQUIRES_PAYMENT_METHOD"},
        {"requires_confirmation", "REQUIRES_PAYMENT_METHOD"},
        {"requires_action", "REQUIRES_ACTION"},
        {"processing", "PROCESSING"},
        {"succeeded", "SUCCEEDED"},
        {"canceled", "CANCELED"},
    };

    private static final List<PaymentStatus> STARTS = List.of(PaymentStatus.values());

    private static StripeOutcome expected(PaymentStatus from, PaymentStatus reported) {
        if (from == reported) {
            return StripeOutcome.UNCHANGED;
        }
        return PaymentFixtures.DIAGRAM.contains(from + "->" + reported)
                ? StripeOutcome.APPLIED
                : StripeOutcome.STALE_IGNORED;
    }

    static Stream<Arguments> everyStatusAgainstEveryReport() {
        return STARTS.stream()
                .flatMap(from -> Arrays.stream(REPORTS)
                        .map(report -> Arguments.of(from, report[0], PaymentStatus.valueOf(report[1]))));
    }

    @ParameterizedTest(name = "{0} + report {1}")
    @MethodSource("everyStatusAgainstEveryReport")
    void aCurrentReportIsAppliedExactlyWhenTheDiagramAllowsIt(
            PaymentStatus from, String stripeStatus, PaymentStatus reported) {
        Payment payment = inStatus(from);
        int historyBefore = payment.history().size();
        var watermarkBefore = payment.lastStripeEventAt();

        StripeOutcome outcome = payment.applyStripeStatus(stripeStatus, at(20), PaymentStatusSource.WEBHOOK, "evt_1");

        assertThat(outcome).isEqualTo(expected(from, reported));
        switch (outcome) {
            case APPLIED -> {
                assertThat(payment.status()).isEqualTo(reported);
                assertThat(payment.lastStripeEventAt()).isEqualTo(at(20));
                assertThat(payment.history()).hasSize(historyBefore + 1);
                assertThat(payment.history().getLast())
                        .isEqualTo(
                                new PaymentStatusChange(from, reported, PaymentStatusSource.WEBHOOK, "evt_1", at(20)));
            }
            case UNCHANGED -> {
                assertThat(payment.status()).isEqualTo(from);
                assertThat(payment.lastStripeEventAt())
                        .as("a current report moves the watermark")
                        .isEqualTo(at(20));
                assertThat(payment.history()).hasSize(historyBefore);
            }
            case STALE_IGNORED -> {
                assertThat(payment.status()).isEqualTo(from);
                assertThat(payment.lastStripeEventAt()).isEqualTo(watermarkBefore);
                assertThat(payment.history()).hasSize(historyBefore);
            }
        }
    }

    @ParameterizedTest(name = "{0} + an older report {1}")
    @MethodSource("everyStatusAgainstEveryReport")
    void anOlderReportIsNeverAppliedWhateverItSays(PaymentStatus from, String stripeStatus, PaymentStatus reported) {
        Payment payment = inStatus(from);
        if (payment.lastStripeEventAt() == null) {
            return; // CREATED, CANCELED, INITIATION_FAILED never saw Stripe: there is nothing for a report to be older
            // than
        }
        var before = payment.lastStripeEventAt();

        StripeOutcome outcome =
                payment.applyStripeStatus(stripeStatus, before.minusSeconds(1), PaymentStatusSource.WEBHOOK, "evt_old");

        assertThat(outcome).isEqualTo(StripeOutcome.STALE_IGNORED);
        assertThat(payment.status()).isEqualTo(from);
        assertThat(payment.lastStripeEventAt()).isEqualTo(before);
    }

    @Test
    void aReportFromTheSameSecondIsNotOlderSoOnlyTheTransitionRuleJudgesIt() {
        Payment payment = inStatus(PaymentStatus.REQUIRES_PAYMENT_METHOD); // watermark at(10)

        assertThat(payment.applyStripeStatus("processing", at(10), PaymentStatusSource.WEBHOOK))
                .isEqualTo(StripeOutcome.APPLIED);
        assertThat(payment.applyStripeStatus("requires_action", at(10), PaymentStatusSource.WEBHOOK))
                .as("same second, but PROCESSING -> REQUIRES_ACTION is not in the diagram")
                .isEqualTo(StripeOutcome.STALE_IGNORED);
        assertThat(payment.applyStripeStatus("succeeded", at(10), PaymentStatusSource.WEBHOOK))
                .isEqualTo(StripeOutcome.APPLIED);
        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    // ---- the cases the spec names ----

    @Test
    void succeededArrivingBeforeProcessingWinsAndTheLateProcessingIsStale() {
        Payment payment = inStatus(PaymentStatus.REQUIRES_PAYMENT_METHOD); // watermark at(10)

        StripeOutcome succeeded =
                payment.applyStripeStatus("succeeded", at(13), PaymentStatusSource.WEBHOOK, "evt_succeeded");
        StripeOutcome processing =
                payment.applyStripeStatus("processing", at(12), PaymentStatusSource.WEBHOOK, "evt_processing");

        assertThat(succeeded).isEqualTo(StripeOutcome.APPLIED);
        assertThat(processing).isEqualTo(StripeOutcome.STALE_IGNORED);
        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.lastStripeEventAt()).isEqualTo(at(13));
        assertThat(payment.history())
                .extracting(PaymentStatusChange::stripeEventId)
                .doesNotContain("evt_processing");
    }

    @Test
    void paymentFailedAfterSucceededIsStaleEvenWhenItIsNewer() {
        Payment payment = inStatus(PaymentStatus.SUCCEEDED); // watermark at(10)

        // payment_intent.payment_failed reports requires_payment_method
        StripeOutcome outcome =
                payment.applyStripeStatus("requires_payment_method", at(15), PaymentStatusSource.WEBHOOK, "evt_failed");

        assertThat(outcome).isEqualTo(StripeOutcome.STALE_IGNORED);
        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.lastStripeEventAt()).isEqualTo(at(10));
    }

    @Test
    void paymentFailedAfterSucceededIsStaleWhenItIsOlderToo() {
        Payment payment = inStatus(PaymentStatus.SUCCEEDED);

        assertThat(payment.applyStripeStatus("requires_payment_method", at(9), PaymentStatusSource.WEBHOOK))
                .isEqualTo(StripeOutcome.STALE_IGNORED);
        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    // ---- the normal life of a payment ----

    @Test
    void aPaymentThatNeedsAuthenticationAndThenSucceeds() {
        Payment payment = inStatus(PaymentStatus.REQUIRES_PAYMENT_METHOD);

        assertThat(payment.applyStripeStatus("requires_action", at(11), PaymentStatusSource.WEBHOOK))
                .isEqualTo(StripeOutcome.APPLIED);
        assertThat(payment.applyStripeStatus("processing", at(12), PaymentStatusSource.WEBHOOK))
                .isEqualTo(StripeOutcome.APPLIED);
        assertThat(payment.applyStripeStatus("succeeded", at(13), PaymentStatusSource.WEBHOOK))
                .isEqualTo(StripeOutcome.APPLIED);

        assertThat(payment.history())
                .extracting(PaymentStatusChange::to)
                .containsExactly(
                        PaymentStatus.CREATED,
                        PaymentStatus.REQUIRES_PAYMENT_METHOD,
                        PaymentStatus.REQUIRES_ACTION,
                        PaymentStatus.PROCESSING,
                        PaymentStatus.SUCCEEDED);
    }

    @Test
    void aFailedAttemptReturnsToRequiresPaymentMethodAndTheCustomerMayTryAgain() {
        Payment payment = inStatus(PaymentStatus.REQUIRES_PAYMENT_METHOD);

        payment.applyStripeStatus("processing", at(11), PaymentStatusSource.WEBHOOK);
        assertThat(payment.applyStripeStatus("requires_payment_method", at(12), PaymentStatusSource.WEBHOOK))
                .isEqualTo(StripeOutcome.APPLIED);
        payment.applyStripeStatus("processing", at(13), PaymentStatusSource.WEBHOOK);
        payment.applyStripeStatus("succeeded", at(14), PaymentStatusSource.WEBHOOK);

        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void failedAuthenticationReturnsToRequiresPaymentMethod() {
        Payment payment = inStatus(PaymentStatus.REQUIRES_ACTION);

        assertThat(payment.applyStripeStatus("requires_payment_method", at(11), PaymentStatusSource.WEBHOOK))
                .isEqualTo(StripeOutcome.APPLIED);
    }

    @Test
    void aSecondDeclineOnAnIntentThatNeverLeftRequiresPaymentMethodIsCurrentButMovesNothing() {
        Payment payment = inStatus(PaymentStatus.REQUIRES_PAYMENT_METHOD);

        StripeOutcome outcome =
                payment.applyStripeStatus("requires_payment_method", at(11), PaymentStatusSource.WEBHOOK);

        assertThat(outcome).isEqualTo(StripeOutcome.UNCHANGED);
        assertThat(payment.lastStripeEventAt()).isEqualTo(at(11));
    }

    @Test
    void aRedeliveredEventIsUnchangedNotAnError() {
        Payment payment = inStatus(PaymentStatus.SUCCEEDED);

        assertThat(payment.applyStripeStatus("succeeded", at(10), PaymentStatusSource.WEBHOOK))
                .isEqualTo(StripeOutcome.UNCHANGED);
        assertThat(payment.history()).hasSize(3);
    }

    // ---- terminal statuses ----

    static Stream<Arguments> terminalStatusesAgainstEveryReport() {
        return Stream.of(PaymentStatus.CANCELED, PaymentStatus.INITIATION_FAILED, PaymentStatus.REFUNDED)
                .flatMap(from -> Arrays.stream(REPORTS).map(report -> Arguments.of(from, report[0], report[1])));
    }

    @ParameterizedTest(name = "{0} stays put when Stripe reports {1}")
    @MethodSource("terminalStatusesAgainstEveryReport")
    void nothingLeavesAFinalStatus(PaymentStatus from, String stripeStatus, String reported) {
        Payment payment = inStatus(from);

        StripeOutcome outcome = payment.applyStripeStatus(stripeStatus, at(99), PaymentStatusSource.RECONCILIATION);

        assertThat(payment.status()).isEqualTo(from);
        assertThat(outcome).isIn(StripeOutcome.STALE_IGNORED, StripeOutcome.UNCHANGED);
        assertThat(outcome == StripeOutcome.UNCHANGED).isEqualTo(from.name().equals(reported));
    }

    @ParameterizedTest(name = "refunded from {0}")
    @EnumSource(PaymentStatus.class)
    void aRefundMovesOnlyASucceededPayment(PaymentStatus from) {
        Payment payment = inStatus(from);

        StripeOutcome outcome = payment.markRefunded(
                UUID.fromString("0199e0a0-7777-7000-8000-000000000007"),
                "re_test_1",
                at(30),
                PaymentStatusSource.WEBHOOK,
                "evt_refunded");

        assertThat(outcome)
                .isEqualTo(
                        switch (from) {
                            case SUCCEEDED -> StripeOutcome.APPLIED;
                            case REFUNDED -> StripeOutcome.UNCHANGED;
                            default -> StripeOutcome.STALE_IGNORED;
                        });
        assertThat(payment.status()).isEqualTo(from == PaymentStatus.SUCCEEDED ? PaymentStatus.REFUNDED : from);
    }

    @Test
    void anOlderRefundReportIsStale() {
        Payment payment = inStatus(PaymentStatus.SUCCEEDED);

        assertThat(payment.markRefunded(
                        UUID.fromString("0199e0a0-7777-7000-8000-000000000007"),
                        "re_test_1",
                        at(9),
                        PaymentStatusSource.WEBHOOK,
                        "evt_old"))
                .isEqualTo(StripeOutcome.STALE_IGNORED);
        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    // ---- refusals ----

    @Test
    void manualCaptureIsRejectedAndChangesNothing() {
        Payment payment = inStatus(PaymentStatus.REQUIRES_PAYMENT_METHOD);

        assertThatThrownBy(() -> payment.applyStripeStatus("requires_capture", at(20), PaymentStatusSource.WEBHOOK))
                .isInstanceOf(StripeConfigurationException.class);
        assertThat(payment.status()).isEqualTo(PaymentStatus.REQUIRES_PAYMENT_METHOD);
        assertThat(payment.lastStripeEventAt()).isEqualTo(at(10));
    }

    @Test
    void anUnknownStatusIsRejectedAndChangesNothing() {
        Payment payment = inStatus(PaymentStatus.REQUIRES_PAYMENT_METHOD);

        assertThatThrownBy(() -> payment.applyStripeStatus("brand_new", at(20), PaymentStatusSource.WEBHOOK))
                .isInstanceOf(UnknownStripeStatusException.class);
        assertThat(payment.lastStripeEventAt()).isEqualTo(at(10));
    }

    @Test
    void aLocalDecisionIsNotAStripeReport() {
        Payment payment = inStatus(PaymentStatus.REQUIRES_PAYMENT_METHOD);

        assertThatThrownBy(() -> payment.applyStripeStatus("processing", at(20), PaymentStatusSource.LOCAL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> payment.applyStripeStatus("processing", null, PaymentStatusSource.WEBHOOK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> payment.applyStripeStatus("processing", at(20), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theFirstReportIsNeverOlderThanNothing() {
        Payment payment = inStatus(PaymentStatus.CREATED);
        assertThat(payment.lastStripeEventAt()).isNull();

        // reached through reconciliation before the worker stored the PaymentIntent: CREATED -> PROCESSING is not
        // allowed
        assertThat(payment.applyStripeStatus("processing", at(1), PaymentStatusSource.RECONCILIATION))
                .isEqualTo(StripeOutcome.STALE_IGNORED);
        assertThat(payment.applyStripeStatus("requires_payment_method", at(1), PaymentStatusSource.RECONCILIATION))
                .isEqualTo(StripeOutcome.APPLIED);
    }

    // ---- every delivery order of a sequence ends in the same place ----

    @Test
    void whicheverOrderTheThreeEventsArriveInThePaymentEndsSucceeded() {
        record Report(String status, int second) {}
        List<Report> events = new ArrayList<>(
                List.of(new Report("requires_action", 11), new Report("processing", 12), new Report("succeeded", 13)));
        List<List<Report>> permutations = new ArrayList<>();
        permute(events, 0, permutations);
        assertThat(permutations).hasSize(6);

        for (List<Report> order : permutations) {
            Payment payment = inStatus(PaymentStatus.REQUIRES_PAYMENT_METHOD);
            order.forEach(e -> payment.applyStripeStatus(e.status(), at(e.second()), PaymentStatusSource.WEBHOOK));

            assertThat(payment.status()).as("delivery order %s", order).isEqualTo(PaymentStatus.SUCCEEDED);
            assertThat(payment.lastStripeEventAt()).isEqualTo(at(13));
        }
    }

    private static <T> void permute(List<T> items, int from, List<List<T>> out) {
        if (from == items.size()) {
            out.add(new ArrayList<>(items));
            return;
        }
        for (int i = from; i < items.size(); i++) {
            java.util.Collections.swap(items, from, i);
            permute(items, from + 1, out);
            java.util.Collections.swap(items, from, i);
        }
    }
}
