package com.altronixsoft.opp.order.domain;

import static com.altronixsoft.opp.order.domain.OrderFixtures.api;
import static com.altronixsoft.opp.order.domain.OrderFixtures.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every command from every status: the allowed ones reach the status of the §5.1 diagram, all the others raise
 * {@link IllegalOrderTransitionException} and change nothing.
 */
class OrderTransitionsTest {

    /** A command under test, the status it needs and the status it leads to. */
    record Command(String name, Consumer<Order> run, Set<OrderStatus> acceptedFrom, OrderStatus leadsTo) {

        @Override
        public String toString() {
            return name;
        }
    }

    static final List<Command> COMMANDS = List.of(
            new Command(
                    "markPaid", o -> o.markPaid(api(10)), EnumSet.of(OrderStatus.PENDING_PAYMENT), OrderStatus.PAID),
            new Command(
                    "cancel(CUSTOMER)",
                    o -> o.cancel(CancelReason.CUSTOMER, api(10)),
                    EnumSet.of(OrderStatus.PENDING_PAYMENT),
                    OrderStatus.CANCELLED),
            new Command(
                    "requestRefund(ADMIN): refund a paid order, or retry a failed refund",
                    o -> o.requestRefund(RefundReason.ADMIN, UUID.randomUUID(), api(10)),
                    EnumSet.of(OrderStatus.PAID, OrderStatus.REFUND_FAILED),
                    OrderStatus.REFUND_REQUESTED),
            new Command(
                    "requestRefund(LATE_PAYMENT_AFTER_CANCEL)",
                    o -> o.requestRefund(RefundReason.LATE_PAYMENT_AFTER_CANCEL, UUID.randomUUID(), api(10)),
                    EnumSet.of(OrderStatus.CANCELLED),
                    OrderStatus.REFUND_REQUESTED),
            new Command(
                    "markRefunded",
                    o -> o.markRefunded(api(10)),
                    EnumSet.of(OrderStatus.REFUND_REQUESTED),
                    OrderStatus.REFUNDED),
            new Command(
                    "markRefundFailed",
                    o -> o.markRefundFailed("card_closed", api(10)),
                    EnumSet.of(OrderStatus.REFUND_REQUESTED),
                    OrderStatus.REFUND_FAILED));

    /** Every (command, status) pair. A pair is allowed when the status is one the command accepts. */
    static Stream<Arguments> allCombinations() {
        return COMMANDS.stream()
                .flatMap(command -> Stream.of(OrderStatus.values()).map(status -> Arguments.of(command, status)));
    }

    private static boolean accepts(Command command, OrderStatus status) {
        return command.acceptedFrom().contains(status);
    }

    @ParameterizedTest(name = "{0} from {1}")
    @MethodSource("allCombinations")
    void aCommandIsAllowedExactlyFromItsStatusAndOtherwiseChangesNothing(Command command, OrderStatus start) {
        Order order = OrderFixtures.inStatus(start);
        int historyBefore = order.history().size();
        var updatedBefore = order.updatedAt();

        if (accepts(command, start)) {
            command.run().accept(order);

            assertThat(order.status()).isEqualTo(command.leadsTo());
            assertThat(order.history()).hasSize(historyBefore + 1);
            StatusChange last = order.history().getLast();
            assertThat(last.from()).isEqualTo(start);
            assertThat(last.to()).isEqualTo(command.leadsTo());
            assertThat(order.pullDomainEvents()).hasSize(1);
            assertThat(order.updatedAt()).isEqualTo(api(10).occurredAt());
        } else {
            assertThatThrownBy(() -> command.run().accept(order))
                    .isInstanceOf(IllegalOrderTransitionException.class)
                    .satisfies(e -> {
                        IllegalOrderTransitionException ex = (IllegalOrderTransitionException) e;
                        assertThat(ex.orderId()).isEqualTo(order.id());
                        assertThat(ex.from()).isEqualTo(start);
                        assertThat(ex.action()).isNotBlank();
                        assertThat(ex.getMessage()).contains(start.name());
                    });

            assertThat(order.status()).as("status untouched").isEqualTo(start);
            assertThat(order.history()).as("no history entry").hasSize(historyBefore);
            assertThat(order.pullDomainEvents()).as("no event").isEmpty();
            assertThat(order.updatedAt()).isEqualTo(updatedBefore);
        }
    }

    @Test
    void theRefundReasonMustFitTheStatusItComesFrom() {
        Order paid = OrderFixtures.inStatus(OrderStatus.PAID);
        Order cancelled = OrderFixtures.inStatus(OrderStatus.CANCELLED);
        Order failed = OrderFixtures.inStatus(OrderStatus.REFUND_FAILED);

        assertThatThrownBy(() -> paid.requestRefund(RefundReason.LATE_PAYMENT_AFTER_CANCEL, UUID.randomUUID(), api(10)))
                .isInstanceOf(IllegalOrderTransitionException.class);
        assertThatThrownBy(() -> cancelled.requestRefund(RefundReason.ADMIN, UUID.randomUUID(), api(10)))
                .isInstanceOf(IllegalOrderTransitionException.class);
        assertThatThrownBy(
                        () -> failed.requestRefund(RefundReason.LATE_PAYMENT_AFTER_CANCEL, UUID.randomUUID(), api(10)))
                .isInstanceOf(IllegalOrderTransitionException.class);

        assertThat(paid.status()).isEqualTo(OrderStatus.PAID);
        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(failed.status()).isEqualTo(OrderStatus.REFUND_FAILED);
    }

    @ParameterizedTest
    @EnumSource(CancelReason.class)
    void everyCancelReasonIsRecordedAndKeptThroughALaterRefund(CancelReason reason) {
        Order order = OrderFixtures.pending();

        order.cancel(reason, event(UUID.randomUUID(), 5));

        assertThat(order.cancelReason()).isEqualTo(reason);
        assertThat(order.history().getLast().reason()).isEqualTo(reason.name());

        order.requestRefund(RefundReason.LATE_PAYMENT_AFTER_CANCEL, UUID.randomUUID(), api(6));

        assertThat(order.cancelReason())
                .as("still known after the compensation starts")
                .isEqualTo(reason);
    }

    @Test
    void thePaymentOutcomeIsFinalOnceTheOrderLeftPendingPayment() {
        Order paid = OrderFixtures.inStatus(OrderStatus.PAID);
        Order cancelled = OrderFixtures.inStatus(OrderStatus.CANCELLED);

        assertThatThrownBy(() -> paid.cancel(CancelReason.TIMEOUT, api(10)))
                .isInstanceOf(IllegalOrderTransitionException.class);
        assertThatThrownBy(() -> cancelled.markPaid(api(10))).isInstanceOf(IllegalOrderTransitionException.class);
        assertThatThrownBy(() -> cancelled.cancel(CancelReason.CUSTOMER, api(10)))
                .isInstanceOf(IllegalOrderTransitionException.class);
    }

    @Test
    void aRefundedOrderCanNeverMoveAgain() {
        Order refunded = OrderFixtures.inStatus(OrderStatus.REFUNDED);

        for (Consumer<Order> command : List.<Consumer<Order>>of(
                o -> o.markPaid(api(10)),
                o -> o.cancel(CancelReason.CUSTOMER, api(10)),
                o -> o.requestRefund(RefundReason.ADMIN, UUID.randomUUID(), api(10)),
                o -> o.requestRefund(RefundReason.LATE_PAYMENT_AFTER_CANCEL, UUID.randomUUID(), api(10)),
                o -> o.markRefunded(api(10)),
                o -> o.markRefundFailed("x", api(10)))) {
            assertThatThrownBy(() -> command.accept(refunded)).isInstanceOf(IllegalOrderTransitionException.class);
        }
        assertThat(refunded.status().isTerminal()).isTrue();
    }

    @Test
    void aFailedRefundCanBeRetriedAnyNumberOfTimes() {
        Order order = OrderFixtures.inStatus(OrderStatus.REFUND_FAILED);

        for (int attempt = 0; attempt < 3; attempt++) {
            order.requestRefund(RefundReason.ADMIN, UUID.randomUUID(), api(10 + 2 * attempt));
            assertThat(order.status()).isEqualTo(OrderStatus.REFUND_REQUESTED);
            order.markRefundFailed("try " + attempt, api(11 + 2 * attempt));
            assertThat(order.status()).isEqualTo(OrderStatus.REFUND_FAILED);
        }
        order.requestRefund(RefundReason.ADMIN, UUID.randomUUID(), api(30));
        order.markRefunded(api(31));

        assertThat(order.status()).isEqualTo(OrderStatus.REFUNDED);
    }

    @Test
    void aFailureReasonIsRequired() {
        Order order = OrderFixtures.inStatus(OrderStatus.REFUND_REQUESTED);

        assertThatThrownBy(() -> order.markRefundFailed(" ", api(10))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> order.markRefundFailed(null, api(10))).isInstanceOf(IllegalArgumentException.class);
        assertThat(order.status()).isEqualTo(OrderStatus.REFUND_REQUESTED);
    }

    @Test
    void nullArgumentsAreRejectedWithoutChangingTheOrder() {
        Order order = OrderFixtures.pending();

        assertThatThrownBy(() -> order.cancel(null, api(1))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> order.markPaid(null)).isInstanceOf(NullPointerException.class);
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.history()).hasSize(1);
    }
}
