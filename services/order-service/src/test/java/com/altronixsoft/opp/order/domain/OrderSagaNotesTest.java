package com.altronixsoft.opp.order.domain;

import static com.altronixsoft.opp.order.domain.OrderFixtures.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Payment attempts that do not decide the payment, and the refund request the order remembers. */
class OrderSagaNotesTest {

    @Test
    void aFailedAttemptIsAHistoryNoteWithoutAnEvent() {
        Order order = OrderFixtures.pending();
        order.pullDomainEvents();
        UUID eventId = UUID.randomUUID();

        order.notePaymentAttemptFailed("card_declined", "generic_decline", event(eventId, 3));

        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.history().getLast())
                .isEqualTo(new StatusChange(
                        OrderStatus.PENDING_PAYMENT,
                        OrderStatus.PENDING_PAYMENT,
                        "PAYMENT_ATTEMPT_FAILED card_declined/generic_decline",
                        TransitionSource.EVENT,
                        eventId,
                        OrderFixtures.T0.plusSeconds(180)));
        assertThat(order.updatedAt()).isEqualTo(OrderFixtures.T0.plusSeconds(180));
        assertThat(order.pullDomainEvents()).isEmpty();
    }

    @Test
    void aFailedAttemptWithoutDeclineCodeKeepsTheErrorCode() {
        Order order = OrderFixtures.pending();

        order.notePaymentAttemptFailed("processing_error", null, event(UUID.randomUUID(), 1));

        assertThat(order.history().getLast().reason()).isEqualTo("PAYMENT_ATTEMPT_FAILED processing_error");
    }

    @Test
    void aFailedAttemptNeedsAnErrorCode() {
        Order order = OrderFixtures.pending();

        assertThatThrownBy(() -> order.notePaymentAttemptFailed(" ", null, event(UUID.randomUUID(), 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> order.notePaymentAttemptFailed(null, null, event(UUID.randomUUID(), 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRequiredActionIsAHistoryNoteWithoutAnEvent() {
        Order order = OrderFixtures.pending();
        order.pullDomainEvents();

        order.notePaymentActionRequired(event(UUID.randomUUID(), 2));

        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.history().getLast().reason()).isEqualTo("PAYMENT_ACTION_REQUIRED");
        assertThat(order.pullDomainEvents()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = "PENDING_PAYMENT", mode = EnumSource.Mode.EXCLUDE)
    void notesAreOnlyTakenWhileAwaitingPayment(OrderStatus status) {
        Order order = OrderFixtures.inStatus(status);
        int history = order.history().size();

        assertThatThrownBy(() -> order.notePaymentActionRequired(event(UUID.randomUUID(), 9)))
                .isInstanceOf(IllegalOrderTransitionException.class);
        assertThatThrownBy(() -> order.notePaymentAttemptFailed("x", null, event(UUID.randomUUID(), 9)))
                .isInstanceOf(IllegalOrderTransitionException.class);
        assertThat(order.history()).hasSize(history);
    }

    @Test
    void theOrderRemembersItsLatestRefundRequest() {
        Order order = OrderFixtures.inStatus(OrderStatus.PAID);
        UUID first = UUID.randomUUID();
        UUID retry = UUID.randomUUID();
        assertThat(order.refundRequestId()).isNull();

        order.requestRefund(RefundReason.ADMIN, first, OrderFixtures.api(5));
        order.markRefundFailed("card_closed", OrderFixtures.api(6));
        assertThat(order.refundRequestId()).isEqualTo(first);

        order.requestRefund(RefundReason.ADMIN, retry, OrderFixtures.api(7));
        assertThat(order.refundRequestId()).isEqualTo(retry);
    }
}
