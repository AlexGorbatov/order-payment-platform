package com.altronixsoft.opp.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.altronixsoft.opp.order.application.PaymentEventCommand.PaymentOutcome;
import com.altronixsoft.opp.order.domain.CancelReason;
import com.altronixsoft.opp.order.domain.Order;
import com.altronixsoft.opp.order.domain.OrderDomainEvent;
import com.altronixsoft.opp.order.domain.OrderFixtures;
import com.altronixsoft.opp.order.domain.OrderStatus;
import com.altronixsoft.opp.order.domain.RefundReason;
import com.altronixsoft.opp.order.domain.StatusChange;
import com.altronixsoft.opp.order.domain.TransitionSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The saga rules of order-service (architecture §6), one payment event against every order status. */
class ApplyPaymentEventServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-08T12:30:00.123456789Z");
    private static final UUID CORRELATION_ID = UUID.fromString("0199e0a0-3333-7000-8000-000000000003");
    private static final UUID NEW_REFUND_ID = UUID.fromString("0199e0a0-4444-7000-8000-000000000004");

    private final InMemoryOrders orders = new InMemoryOrders();
    private final RecordingEventPublisher events = new RecordingEventPublisher();
    private final ApplyPaymentEventService service =
            new ApplyPaymentEventService(orders, events, () -> NEW_REFUND_ID, Clock.fixed(NOW, ZoneOffset.UTC));

    private Order stored(OrderStatus status) {
        Order order = OrderFixtures.inStatus(status);
        orders.stored.put(order.id(), order);
        return order;
    }

    private static PaymentEventCommand command(Order order, PaymentOutcome outcome) {
        return new PaymentEventCommand(UUID.randomUUID(), order.id(), CORRELATION_ID, outcome);
    }

    private PaymentEventResult apply(Order order, PaymentOutcome outcome) {
        return service.apply(command(order, outcome));
    }

    private void assertUnchanged(Order order, int historySize) {
        assertThat(order.history()).hasSize(historySize);
        assertThat(orders.saves).isZero();
        assertThat(events.published).isEmpty();
    }

    @Nested
    class PaymentSucceeded {

        @Test
        void marksAPendingOrderPaid() {
            Order order = stored(OrderStatus.PENDING_PAYMENT);
            PaymentEventCommand command = command(order, new PaymentOutcome.Succeeded());

            assertThat(service.apply(command)).isEqualTo(PaymentEventResult.APPLIED);

            assertThat(order.status()).isEqualTo(OrderStatus.PAID);
            StatusChange change = order.history().getLast();
            assertThat(change.source()).isEqualTo(TransitionSource.EVENT);
            assertThat(change.sourceEventId()).isEqualTo(command.eventId());
            assertThat(change.occurredAt()).isEqualTo(Instant.parse("2026-10-08T12:30:00.123456Z"));
            assertThat(orders.saves).isEqualTo(1);
            assertThat(events.events()).singleElement().isInstanceOf(OrderDomainEvent.Paid.class);
        }

        @Test
        @DisplayName("F18: a payment that succeeds after the cancellation is refunded automatically")
        void compensatesACancelledOrderWithARefund() {
            Order order = stored(OrderStatus.CANCELLED);
            PaymentEventCommand command = command(order, new PaymentOutcome.Succeeded());

            assertThat(service.apply(command)).isEqualTo(PaymentEventResult.COMPENSATED);

            assertThat(order.status()).isEqualTo(OrderStatus.REFUND_REQUESTED);
            assertThat(order.refundRequestId()).isEqualTo(NEW_REFUND_ID);
            assertThat(order.history().getLast().sourceEventId()).isEqualTo(command.eventId());
            assertThat(events.published).singleElement().satisfies(published -> {
                assertThat(published.correlationId()).isEqualTo(CORRELATION_ID);
                assertThat(published.event()).isInstanceOfSatisfying(OrderDomainEvent.RefundRequested.class, refund -> {
                    assertThat(refund.reason()).isEqualTo(RefundReason.LATE_PAYMENT_AFTER_CANCEL);
                    assertThat(refund.refundRequestId()).isEqualTo(NEW_REFUND_ID);
                    assertThat(refund.amount()).isEqualTo(order.total());
                    assertThat(refund.trigger().sourceEventId()).isEqualTo(command.eventId());
                });
            });
        }

        @ParameterizedTest
        @EnumSource(
                value = OrderStatus.class,
                names = {"PAID", "REFUND_REQUESTED", "REFUNDED", "REFUND_FAILED"})
        void isALateDuplicateOnceThePaymentWasApplied(OrderStatus status) {
            Order order = stored(status);
            int history = order.history().size();

            assertThat(apply(order, new PaymentOutcome.Succeeded())).isEqualTo(PaymentEventResult.IGNORED);

            assertThat(order.status()).isEqualTo(status);
            assertUnchanged(order, history);
        }
    }

    @Nested
    class PaymentWillNotHappen {

        @Test
        void initiationFailureCancelsAPendingOrder() {
            Order order = stored(OrderStatus.PENDING_PAYMENT);

            assertThat(apply(order, new PaymentOutcome.InitiationFailed("card_declined")))
                    .isEqualTo(PaymentEventResult.APPLIED);

            assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order.cancelReason()).isEqualTo(CancelReason.PAYMENT_INITIATION_FAILED);
            assertThat(events.published).singleElement().satisfies(published -> {
                assertThat(published.correlationId()).isEqualTo(CORRELATION_ID);
                assertThat(published.event())
                        .isInstanceOfSatisfying(
                                OrderDomainEvent.Cancelled.class,
                                cancelled -> assertThat(cancelled.reason())
                                        .isEqualTo(CancelReason.PAYMENT_INITIATION_FAILED));
            });
        }

        @Test
        void cancellationAtTheProviderCancelsAPendingOrder() {
            Order order = stored(OrderStatus.PENDING_PAYMENT);

            assertThat(apply(order, new PaymentOutcome.Canceled("abandoned"))).isEqualTo(PaymentEventResult.APPLIED);

            assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order.cancelReason()).isEqualTo(CancelReason.PAYMENT_CANCELED);
        }

        @ParameterizedTest
        @EnumSource(value = OrderStatus.class, names = "PENDING_PAYMENT", mode = EnumSource.Mode.EXCLUDE)
        void changesNothingOnceTheOrderMovedOn(OrderStatus status) {
            Order initiation = stored(status);
            Order cancellation = stored(status);
            int history = initiation.history().size();

            assertThat(apply(initiation, new PaymentOutcome.InitiationFailed("x")))
                    .isEqualTo(PaymentEventResult.IGNORED);
            assertThat(apply(cancellation, new PaymentOutcome.Canceled("x"))).isEqualTo(PaymentEventResult.IGNORED);

            assertThat(initiation.status()).isEqualTo(status);
            assertThat(cancellation.status()).isEqualTo(status);
            assertUnchanged(initiation, history);
        }
    }

    @Nested
    class AttemptsThatDoNotDecideThePayment {

        @Test
        void aFailedAttemptIsOnlyRecordedInTheHistory() {
            Order order = stored(OrderStatus.PENDING_PAYMENT);

            assertThat(apply(order, new PaymentOutcome.AttemptFailed("card_declined", "insufficient_funds")))
                    .isEqualTo(PaymentEventResult.APPLIED);

            assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
            assertThat(order.history().getLast().reason())
                    .isEqualTo("PAYMENT_ATTEMPT_FAILED card_declined/insufficient_funds");
            assertThat(orders.saves).isEqualTo(1);
            assertThat(events.published).isEmpty();
        }

        @Test
        void aRequiredActionIsOnlyRecordedInTheHistory() {
            Order order = stored(OrderStatus.PENDING_PAYMENT);

            assertThat(apply(order, new PaymentOutcome.ActionRequired())).isEqualTo(PaymentEventResult.APPLIED);

            assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
            assertThat(order.history().getLast().reason()).isEqualTo("PAYMENT_ACTION_REQUIRED");
            assertThat(events.published).isEmpty();
        }

        @ParameterizedTest
        @EnumSource(value = OrderStatus.class, names = "PENDING_PAYMENT", mode = EnumSource.Mode.EXCLUDE)
        void areStaleOnceTheOrderMovedOn(OrderStatus status) {
            Order order = stored(status);
            int history = order.history().size();

            assertThat(apply(order, new PaymentOutcome.AttemptFailed("card_declined", null)))
                    .isEqualTo(PaymentEventResult.IGNORED);
            assertThat(apply(order, new PaymentOutcome.ActionRequired())).isEqualTo(PaymentEventResult.IGNORED);

            assertUnchanged(order, history);
        }
    }

    @Nested
    class RefundOutcomes {

        @Test
        void aSuccessfulRefundCompletesTheRequest() {
            Order order = stored(OrderStatus.REFUND_REQUESTED);

            assertThat(apply(order, new PaymentOutcome.Refunded(order.refundRequestId())))
                    .isEqualTo(PaymentEventResult.APPLIED);

            assertThat(order.status()).isEqualTo(OrderStatus.REFUNDED);
            assertThat(events.events()).singleElement().isInstanceOf(OrderDomainEvent.Refunded.class);
        }

        @Test
        void aFailedRefundLetsTheAdministratorRetry() {
            Order order = stored(OrderStatus.REFUND_REQUESTED);

            assertThat(apply(order, new PaymentOutcome.RefundFailed(order.refundRequestId(), "charge_disputed")))
                    .isEqualTo(PaymentEventResult.APPLIED);

            assertThat(order.status()).isEqualTo(OrderStatus.REFUND_FAILED);
            assertThat(order.history().getLast().reason()).isEqualTo("charge_disputed");
        }

        @Test
        void repeatedOutcomesOfTheSameRequestAreDuplicates() {
            Order refunded = stored(OrderStatus.REFUNDED);
            Order failed = stored(OrderStatus.REFUND_FAILED);

            assertThat(apply(refunded, new PaymentOutcome.Refunded(refunded.refundRequestId())))
                    .isEqualTo(PaymentEventResult.IGNORED);
            assertThat(apply(failed, new PaymentOutcome.RefundFailed(failed.refundRequestId(), "x")))
                    .isEqualTo(PaymentEventResult.IGNORED);
            assertThat(orders.saves).isZero();
        }

        @Test
        @DisplayName("ADR-0007: the outcome of an older refund request arriving after a retry is stale")
        void anOutcomeOfAnOlderRequestIsStale() {
            Order order = stored(OrderStatus.REFUND_REQUESTED);
            int history = order.history().size();
            UUID older = UUID.randomUUID();

            assertThat(apply(order, new PaymentOutcome.RefundFailed(older, "card_closed")))
                    .isEqualTo(PaymentEventResult.IGNORED);
            assertThat(apply(order, new PaymentOutcome.Refunded(older))).isEqualTo(PaymentEventResult.IGNORED);

            assertThat(order.status()).isEqualTo(OrderStatus.REFUND_REQUESTED);
            assertUnchanged(order, history);
        }

        @ParameterizedTest
        @EnumSource(
                value = OrderStatus.class,
                names = {"PENDING_PAYMENT", "PAID", "CANCELLED"})
        void anOrderThatNeverRequestedARefundCannotHaveAnOutcome(OrderStatus status) {
            Order order = stored(status);

            assertThatThrownBy(() -> apply(order, new PaymentOutcome.Refunded(UUID.randomUUID())))
                    .isInstanceOf(UnexpectedPaymentEventException.class)
                    .hasMessageContaining("never requested a refund");
            assertThatThrownBy(() -> apply(order, new PaymentOutcome.RefundFailed(UUID.randomUUID(), "x")))
                    .isInstanceOf(UnexpectedPaymentEventException.class);
            assertThat(order.status()).isEqualTo(status);
        }

        @Test
        void contradictoryOutcomesOfTheSameRequestAreRejected() {
            Order refunded = stored(OrderStatus.REFUNDED);
            Order failed = stored(OrderStatus.REFUND_FAILED);

            assertThatThrownBy(() -> apply(refunded, new PaymentOutcome.RefundFailed(refunded.refundRequestId(), "x")))
                    .isInstanceOf(UnexpectedPaymentEventException.class)
                    .hasMessageContaining("contradicts");
            assertThatThrownBy(() -> apply(failed, new PaymentOutcome.Refunded(failed.refundRequestId())))
                    .isInstanceOf(UnexpectedPaymentEventException.class);
            assertThat(refunded.status()).isEqualTo(OrderStatus.REFUNDED);
            assertThat(failed.status()).isEqualTo(OrderStatus.REFUND_FAILED);
        }
    }

    @Nested
    class Disputes {

        @ParameterizedTest
        @EnumSource(OrderStatus.class)
        void flagTheOrderInAnyStatusOnce(OrderStatus status) {
            Order order = stored(status);

            assertThat(apply(order, new PaymentOutcome.Disputed("dp_1"))).isEqualTo(PaymentEventResult.APPLIED);
            assertThat(apply(order, new PaymentOutcome.Disputed("dp_1"))).isEqualTo(PaymentEventResult.IGNORED);

            assertThat(order.disputed()).isTrue();
            assertThat(order.status()).isEqualTo(status);
            assertThat(orders.saves).isEqualTo(1);
        }
    }

    @Test
    void anEventForAnUnknownOrderIsRejected() {
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> service.apply(new PaymentEventCommand(
                        UUID.randomUUID(), unknown, CORRELATION_ID, new PaymentOutcome.Succeeded())))
                .isInstanceOfSatisfying(
                        UnexpectedPaymentEventException.class,
                        e -> assertThat(e.orderId()).isEqualTo(unknown));
    }

    @Test
    void everyOutcomeIsHandledForEveryStatus() {
        PaymentOutcome[] outcomes = {
            new PaymentOutcome.Succeeded(),
            new PaymentOutcome.InitiationFailed("x"),
            new PaymentOutcome.Canceled("x"),
            new PaymentOutcome.AttemptFailed("x", null),
            new PaymentOutcome.ActionRequired(),
            new PaymentOutcome.Disputed("dp_1")
        };
        for (OrderStatus status : EnumSet.allOf(OrderStatus.class)) {
            for (PaymentOutcome outcome : outcomes) {
                Order order = stored(status);
                assertThat(apply(order, outcome))
                        .as("%s on %s", outcome, status)
                        .isNotNull();
            }
        }
    }

    @Test
    void aCommandNeedsAllItsParts() {
        UUID id = UUID.randomUUID();
        PaymentOutcome outcome = new PaymentOutcome.Succeeded();

        assertThatThrownBy(() -> new PaymentEventCommand(null, id, id, outcome))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentEventCommand(id, null, id, outcome))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentEventCommand(id, id, null, outcome))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentEventCommand(id, id, id, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentOutcome.Refunded(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentOutcome.RefundFailed(null, "x")).isInstanceOf(NullPointerException.class);
    }
}
