package com.altronixsoft.opp.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The transition table against an independent transcription of the diagram in architecture §5.1. */
class OrderStatusTest {

    /** Every edge of the §5.1 state diagram, written out as pairs — and nothing else is allowed. */
    private static final Map<OrderStatus, Set<OrderStatus>> DIAGRAM = Map.of(
            OrderStatus.PENDING_PAYMENT, EnumSet.of(OrderStatus.PAID, OrderStatus.CANCELLED),
            OrderStatus.PAID, EnumSet.of(OrderStatus.REFUND_REQUESTED),
            OrderStatus.CANCELLED, EnumSet.of(OrderStatus.REFUND_REQUESTED),
            OrderStatus.REFUND_REQUESTED, EnumSet.of(OrderStatus.REFUNDED, OrderStatus.REFUND_FAILED),
            OrderStatus.REFUND_FAILED, EnumSet.of(OrderStatus.REFUND_REQUESTED),
            OrderStatus.REFUNDED, EnumSet.noneOf(OrderStatus.class));

    static Stream<Arguments> allPairs() {
        return Stream.of(OrderStatus.values())
                .flatMap(from -> Stream.of(OrderStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allPairs")
    void everyPairIsAllowedExactlyWhenTheDiagramHasThatEdge(OrderStatus from, OrderStatus to) {
        assertThat(from.canTransitionTo(to)).isEqualTo(DIAGRAM.get(from).contains(to));
    }

    @Test
    void theDiagramCoversEveryStatus() {
        assertThat(DIAGRAM.keySet()).containsExactlyInAnyOrder(OrderStatus.values());
    }

    @ParameterizedTest
    @MethodSource("statuses")
    void allowedTargetsMatchTheDiagram(OrderStatus from) {
        assertThat(from.allowedTargets()).containsExactlyInAnyOrderElementsOf(DIAGRAM.get(from));
    }

    @ParameterizedTest
    @MethodSource("statuses")
    void onlyRefundedIsTerminal(OrderStatus status) {
        assertThat(status.isTerminal()).isEqualTo(status == OrderStatus.REFUNDED);
    }

    @Test
    void theReturnedSetIsACopy() {
        Set<OrderStatus> targets = OrderStatus.PENDING_PAYMENT.allowedTargets();
        targets.clear();

        assertThat(OrderStatus.PENDING_PAYMENT.allowedTargets()).hasSize(2);
        OrderStatus.REFUNDED.allowedTargets().add(OrderStatus.PAID);
        assertThat(OrderStatus.REFUNDED.allowedTargets()).isEmpty();
    }

    static Stream<OrderStatus> statuses() {
        return Stream.of(OrderStatus.values());
    }
}
