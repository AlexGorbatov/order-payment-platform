package com.altronixsoft.opp.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class PaymentStatusTest {

    static Stream<Arguments> allPairs() {
        return Arrays.stream(PaymentStatus.values())
                .flatMap(from -> Arrays.stream(PaymentStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allPairs")
    void everyPairIsAllowedExactlyWhenTheDiagramHasIt(PaymentStatus from, PaymentStatus to) {
        assertThat(from.canTransitionTo(to)).isEqualTo(PaymentFixtures.DIAGRAM.contains(from + "->" + to));
        assertThat(from.allowedTargets().contains(to)).isEqualTo(from.canTransitionTo(to));
    }

    @Test
    void theDiagramHasFourteenTransitions() {
        long allowed = allPairs()
                .filter(a -> ((PaymentStatus) a.get()[0]).canTransitionTo((PaymentStatus) a.get()[1]))
                .count();

        assertThat(allowed).isEqualTo(14).isEqualTo(PaymentFixtures.DIAGRAM.size());
    }

    @ParameterizedTest
    @EnumSource(
            value = PaymentStatus.class,
            names = {"SUCCEEDED", "CANCELED", "INITIATION_FAILED", "REFUNDED"})
    void terminalStatusesAreTheOnesTheSpecNames(PaymentStatus status) {
        assertThat(status.isTerminal()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(
            value = PaymentStatus.class,
            names = {"CREATED", "REQUIRES_PAYMENT_METHOD", "REQUIRES_ACTION", "PROCESSING"})
    void theOthersAreNot(PaymentStatus status) {
        assertThat(status.isTerminal()).isFalse();
        assertThat(status.allowedTargets()).isNotEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = PaymentStatus.class,
            names = {"CANCELED", "INITIATION_FAILED", "REFUNDED"})
    void nothingLeavesAFinalStatus(PaymentStatus status) {
        assertThat(status.allowedTargets()).isEmpty();
    }

    @Test
    void theOnlyWayOutOfSucceededIsRefunded() {
        assertThat(PaymentStatus.SUCCEEDED.allowedTargets()).containsExactly(PaymentStatus.REFUNDED);
    }

    @Test
    void nothingGoesBackToCreatedAndAProcessingPaymentCannotBeCanceled() {
        assertThat(Arrays.stream(PaymentStatus.values()).filter(s -> s.canTransitionTo(PaymentStatus.CREATED)))
                .isEmpty();
        assertThat(PaymentStatus.PROCESSING.canTransitionTo(PaymentStatus.CANCELED))
                .isFalse();
    }

    @Test
    void onlyTheStatusesStripeCanStillCancelAreCancelable() {
        assertThat(Arrays.stream(PaymentStatus.values()).filter(PaymentStatus::isCancelableAtStripe))
                .containsExactly(PaymentStatus.REQUIRES_PAYMENT_METHOD, PaymentStatus.REQUIRES_ACTION);
    }

    @Test
    void allowedTargetsIsACopy() {
        PaymentStatus.CREATED.allowedTargets().clear();

        assertThat(PaymentStatus.CREATED.allowedTargets()).isNotEmpty();
    }
}
