package com.altronixsoft.opp.order.domain;

import static com.altronixsoft.opp.order.domain.OrderFixtures.api;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Model-based check over <b>all</b> sequences of commands up to a fixed length (not a random sample): a tiny model,
 * written independently of {@link OrderStatus}, says what every command does in every status; the real aggregate must
 * agree after every step of every sequence.
 *
 * <p>Properties checked along the way: no way out of {@code REFUNDED}; only the diagram's edges are ever taken; a
 * rejected command changes nothing; the history is an unbroken chain; the dispute flag never goes back; every accepted
 * command registered exactly one event.
 */
class OrderStateMachineExhaustiveTest {

    private enum Action {
        PAID,
        CANCEL,
        REFUND_ADMIN,
        REFUND_LATE,
        REFUNDED,
        REFUND_FAILED,
        DISPUTE
    }

    private static final int DEPTH = 6;

    /** The §5.1 diagram as a function: where does {@code action} lead from {@code status}? Empty = not allowed. */
    private static Optional<OrderStatus> model(OrderStatus status, Action action) {
        return switch (action) {
            case PAID -> status == OrderStatus.PENDING_PAYMENT ? Optional.of(OrderStatus.PAID) : Optional.empty();
            case CANCEL ->
                status == OrderStatus.PENDING_PAYMENT ? Optional.of(OrderStatus.CANCELLED) : Optional.empty();
            case REFUND_ADMIN ->
                status == OrderStatus.PAID || status == OrderStatus.REFUND_FAILED
                        ? Optional.of(OrderStatus.REFUND_REQUESTED)
                        : Optional.empty();
            case REFUND_LATE ->
                status == OrderStatus.CANCELLED ? Optional.of(OrderStatus.REFUND_REQUESTED) : Optional.empty();
            case REFUNDED ->
                status == OrderStatus.REFUND_REQUESTED ? Optional.of(OrderStatus.REFUNDED) : Optional.empty();
            case REFUND_FAILED ->
                status == OrderStatus.REFUND_REQUESTED ? Optional.of(OrderStatus.REFUND_FAILED) : Optional.empty();
            case DISPUTE -> Optional.of(status);
        };
    }

    private static void apply(Order order, Action action, int step) {
        switch (action) {
            case PAID -> order.markPaid(api(step));
            case CANCEL -> order.cancel(CancelReason.CUSTOMER, api(step));
            case REFUND_ADMIN -> order.requestRefund(RefundReason.ADMIN, UUID.randomUUID(), api(step));
            case REFUND_LATE ->
                order.requestRefund(RefundReason.LATE_PAYMENT_AFTER_CANCEL, UUID.randomUUID(), api(step));
            case REFUNDED -> order.markRefunded(api(step));
            case REFUND_FAILED -> order.markRefundFailed("failed", api(step));
            case DISPUTE -> order.markDisputed(api(step));
        }
    }

    @Test
    void everySequenceUpToTheDepthAgreesWithTheModel() {
        long[] sequences = new long[1];
        long[] accepted = new long[1];
        long[] rejected = new long[1];
        explore(new ArrayList<>(), sequences, accepted, rejected);

        // 7 actions, depth 6: 7 + 7^2 + ... + 7^6 sequences, each replayed from a fresh order
        assertThat(sequences[0]).isEqualTo(7L + 49 + 343 + 2401 + 16807 + 117649);
        assertThat(accepted[0]).isPositive();
        assertThat(rejected[0]).isPositive();
    }

    private void explore(List<Action> prefix, long[] sequences, long[] accepted, long[] rejected) {
        if (prefix.size() == DEPTH) {
            return;
        }
        for (Action next : Action.values()) {
            List<Action> sequence = new ArrayList<>(prefix);
            sequence.add(next);
            sequences[0]++;
            replayAndCheck(sequence, accepted, rejected);
            explore(sequence, sequences, accepted, rejected);
        }
    }

    private void replayAndCheck(List<Action> sequence, long[] accepted, long[] rejected) {
        Order order = OrderFixtures.pending();
        order.pullDomainEvents();
        OrderStatus expectedStatus = OrderStatus.PENDING_PAYMENT;
        boolean expectedDisputed = false;
        int expectedHistory = 1;

        for (int step = 0; step < sequence.size(); step++) {
            Action action = sequence.get(step);
            Optional<OrderStatus> expected = model(expectedStatus, action);
            boolean repeatedDispute = action == Action.DISPUTE && expectedDisputed;
            Consumer<Order> run = o -> apply(o, action, 1 + sequence.indexOf(action));

            if (expected.isPresent()) {
                apply(order, action, 100 + step);
                accepted[0]++;
                expectedStatus = expected.get();
                if (action == Action.DISPUTE) {
                    expectedDisputed = true;
                }
                if (!repeatedDispute) {
                    expectedHistory++;
                }
                assertThat(order.pullDomainEvents())
                        .as("events after %s", sequence.subList(0, step + 1))
                        .hasSize(repeatedDispute ? 0 : 1);
            } else {
                rejected[0]++;
                int before = order.history().size();
                try {
                    run.accept(order);
                    throw new AssertionError("expected a rejection of " + action + " in " + expectedStatus + " after "
                            + sequence.subList(0, step));
                } catch (IllegalOrderTransitionException expectedRejection) {
                    assertThat(order.history()).hasSize(before);
                    assertThat(order.pullDomainEvents()).isEmpty();
                }
            }

            assertThat(order.status())
                    .as("status after %s", sequence.subList(0, step + 1))
                    .isEqualTo(expectedStatus);
            assertThat(order.disputed()).isEqualTo(expectedDisputed);
            assertThat(order.history()).hasSize(expectedHistory);
        }

        assertThat(chainIsUnbroken(order.history())).isTrue();
        assertThat(order.status().isTerminal())
                .as("REFUNDED is the only status without a way out")
                .isEqualTo(order.status() == OrderStatus.REFUNDED);
    }

    private static boolean chainIsUnbroken(List<StatusChange> history) {
        if (history.getFirst().from() != null) {
            return false;
        }
        for (int i = 1; i < history.size(); i++) {
            StatusChange change = history.get(i);
            boolean sameStatus = change.from() == change.to();
            boolean followsPrevious = change.from() == history.get(i - 1).to();
            boolean allowed = sameStatus || change.from().canTransitionTo(change.to());
            if (!followsPrevious || !allowed) {
                return false;
            }
        }
        return true;
    }
}
