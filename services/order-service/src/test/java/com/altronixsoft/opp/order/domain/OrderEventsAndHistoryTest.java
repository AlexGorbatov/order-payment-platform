package com.altronixsoft.opp.order.domain;

import static com.altronixsoft.opp.order.domain.OrderFixtures.api;
import static com.altronixsoft.opp.order.domain.OrderFixtures.event;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Each accepted command registers exactly one event and one history entry carrying the trigger. */
class OrderEventsAndHistoryTest {

    private final UUID eventId = UUID.randomUUID();

    @Test
    void markPaid() {
        Order order = OrderFixtures.pending();
        order.pullDomainEvents();
        Trigger trigger = event(eventId, 1);

        order.markPaid(trigger);

        assertThat(order.pullDomainEvents()).containsExactly(new OrderDomainEvent.Paid(order.id(), trigger));
        assertThat(order.history().getLast())
                .isEqualTo(new StatusChange(
                        OrderStatus.PENDING_PAYMENT,
                        OrderStatus.PAID,
                        null,
                        TransitionSource.EVENT,
                        eventId,
                        trigger.occurredAt()));
    }

    @ParameterizedTest
    @EnumSource(CancelReason.class)
    void cancel(CancelReason reason) {
        Order order = OrderFixtures.pending();
        order.pullDomainEvents();
        Trigger trigger = Trigger.job(api(1).occurredAt());

        order.cancel(reason, trigger);

        assertThat(order.pullDomainEvents())
                .containsExactly(new OrderDomainEvent.Cancelled(order.id(), reason, trigger));
        assertThat(order.history().getLast())
                .isEqualTo(new StatusChange(
                        OrderStatus.PENDING_PAYMENT,
                        OrderStatus.CANCELLED,
                        reason.name(),
                        TransitionSource.JOB,
                        null,
                        trigger.occurredAt()));
    }

    @Test
    void requestRefundCarriesTheWholeTotalAndTheRequestId() {
        Order order = OrderFixtures.inStatus(OrderStatus.PAID);
        UUID refundRequestId = UUID.randomUUID();
        Trigger trigger = api(5);

        order.requestRefund(RefundReason.ADMIN, refundRequestId, trigger);

        assertThat(order.pullDomainEvents())
                .containsExactly(new OrderDomainEvent.RefundRequested(
                        order.id(), refundRequestId, Money.of(3097, "EUR"), RefundReason.ADMIN, trigger));
        assertThat(order.history().getLast().reason()).isEqualTo("ADMIN");
        assertThat(order.history().getLast().source()).isEqualTo(TransitionSource.API);
    }

    @Test
    void theAutomaticCompensationAfterALatePaymentIsAnEventTriggeredRefund() {
        Order order = OrderFixtures.inStatus(OrderStatus.CANCELLED);
        Trigger trigger = event(eventId, 5);

        order.requestRefund(RefundReason.LATE_PAYMENT_AFTER_CANCEL, UUID.randomUUID(), trigger);

        assertThat(order.status()).isEqualTo(OrderStatus.REFUND_REQUESTED);
        assertThat(order.history().getLast().from()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.history().getLast().sourceEventId()).isEqualTo(eventId);
        assertThat(order.pullDomainEvents())
                .singleElement()
                .isInstanceOfSatisfying(OrderDomainEvent.RefundRequested.class, e -> {
                    assertThat(e.reason()).isEqualTo(RefundReason.LATE_PAYMENT_AFTER_CANCEL);
                    assertThat(e.amount()).isEqualTo(order.total());
                });
    }

    @Test
    void markRefundedAndMarkRefundFailed() {
        Order refunded = OrderFixtures.inStatus(OrderStatus.REFUND_REQUESTED);
        Order failed = OrderFixtures.inStatus(OrderStatus.REFUND_REQUESTED);
        Trigger trigger = event(eventId, 9);

        refunded.markRefunded(trigger);
        failed.markRefundFailed("insufficient_balance", trigger);

        assertThat(refunded.pullDomainEvents()).containsExactly(new OrderDomainEvent.Refunded(refunded.id(), trigger));
        assertThat(failed.pullDomainEvents())
                .containsExactly(new OrderDomainEvent.RefundFailed(failed.id(), "insufficient_balance", trigger));
        assertThat(failed.history().getLast().reason()).isEqualTo("insufficient_balance");
        assertThat(refunded.history().getLast().to()).isEqualTo(OrderStatus.REFUNDED);
    }

    @Test
    void aLifecycleKeepsAnUnbrokenHistory() {
        Order order = OrderFixtures.pending();
        order.markPaid(api(1));
        order.requestRefund(RefundReason.ADMIN, UUID.randomUUID(), api(2));
        order.markRefundFailed("x", api(3));
        order.requestRefund(RefundReason.ADMIN, UUID.randomUUID(), api(4));
        order.markRefunded(api(5));

        List<StatusChange> history = order.history();

        assertThat(history)
                .extracting(StatusChange::to)
                .containsExactly(
                        OrderStatus.PENDING_PAYMENT,
                        OrderStatus.PAID,
                        OrderStatus.REFUND_REQUESTED,
                        OrderStatus.REFUND_FAILED,
                        OrderStatus.REFUND_REQUESTED,
                        OrderStatus.REFUNDED);
        assertThat(history.getFirst().from()).isNull();
        for (int i = 1; i < history.size(); i++) {
            assertThat(history.get(i).from()).isEqualTo(history.get(i - 1).to());
        }
        assertThat(order.pullDomainEvents()).hasSize(6);
        assertThat(order.updatedAt()).isEqualTo(api(5).occurredAt());
        assertThat(order.createdAt()).isEqualTo(api(0).occurredAt());
    }

    // -------------------------------------------------------------------------------------------- dispute

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    void aDisputeSetsTheFlagInAnyStatusWithoutMovingTheStatus(OrderStatus status) {
        Order order = OrderFixtures.inStatus(status);
        Trigger trigger = event(eventId, 20);

        order.markDisputed(trigger);

        assertThat(order.disputed()).isTrue();
        assertThat(order.status()).isEqualTo(status);
        assertThat(order.updatedAt()).isEqualTo(trigger.occurredAt());
        assertThat(order.history().getLast())
                .isEqualTo(new StatusChange(
                        status, status, "DISPUTED", TransitionSource.EVENT, eventId, trigger.occurredAt()));
        assertThat(order.pullDomainEvents()).containsExactly(new OrderDomainEvent.Disputed(order.id(), trigger));
    }

    @Test
    void aSecondDisputeChangesNothing() {
        Order order = OrderFixtures.inStatus(OrderStatus.PAID);
        order.markDisputed(event(eventId, 20));
        order.pullDomainEvents();
        int history = order.history().size();

        order.markDisputed(event(UUID.randomUUID(), 25));

        assertThat(order.history()).hasSize(history);
        assertThat(order.pullDomainEvents()).isEmpty();
        assertThat(order.updatedAt()).isEqualTo(event(eventId, 20).occurredAt());
    }

    @Test
    void theDisputeFlagSurvivesTheLaterLifecycle() {
        Order order = OrderFixtures.inStatus(OrderStatus.PAID);
        order.markDisputed(api(5));

        order.requestRefund(RefundReason.ADMIN, UUID.randomUUID(), api(6));
        order.markRefunded(api(7));

        assertThat(order.disputed()).isTrue();
    }

    // -------------------------------------------------------------------------------------------- triggers

    @Test
    void anEventTriggerNeedsAnEventIdAndOthersMustNotHaveOne() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new Trigger(TransitionSource.EVENT, null, api(0).occurredAt()))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new Trigger(TransitionSource.API, UUID.randomUUID(), api(0).occurredAt()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Trigger.api(api(0).occurredAt()).sourceEventId()).isNull();
        assertThat(Trigger.job(api(0).occurredAt()).source()).isEqualTo(TransitionSource.JOB);
    }

    @Test
    void theEventKnowsWhenItHappened() {
        Order order = OrderFixtures.pending();

        assertThat(order.pullDomainEvents()).singleElement().satisfies(e -> {
            assertThat(e.occurredAt()).isEqualTo(api(0).occurredAt());
            assertThat(e.orderId()).isEqualTo(order.id());
            assertThat(e.trigger()).isEqualTo(api(0));
        });
    }

    @Test
    void theProductRejectsAnInvalidDefinition() {
        Money price = Money.of(100, "EUR");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new Product("", "Name", price, true))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new Product("SKU", " ", price, true))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new Product("SKU", "Name", Money.of(0, "EUR"), true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new Product("SKU", "Name", price, false).active()).isFalse();
    }
}
