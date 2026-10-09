package com.altronixsoft.opp.payment.domain;

import static com.altronixsoft.opp.payment.domain.PaymentFixtures.PI;
import static com.altronixsoft.opp.payment.domain.PaymentFixtures.at;
import static com.altronixsoft.opp.payment.domain.PaymentFixtures.inStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The events a payment registers for what Stripe reports (architecture §9.3), and only for news. */
class PaymentWebhookEventsTest {

    private static final UUID REFUND_REQUEST = UUID.fromString("0199e0a0-8888-7000-8000-000000000008");

    private static Payment pending() {
        Payment payment = inStatus(PaymentStatus.REQUIRES_PAYMENT_METHOD);
        payment.pullDomainEvents();
        return payment;
    }

    @Test
    void successRegistersSucceededWithAmountAndPaymentIntent() {
        Payment payment = pending();

        payment.applyStripeStatus("succeeded", at(20), PaymentStatusSource.WEBHOOK, "evt_1");

        assertThat(payment.pullDomainEvents())
                .containsExactly(new PaymentDomainEvent.Succeeded(
                        payment.id(), payment.orderId(), payment.amount(), PI, at(20)));
    }

    @Test
    void aRequiredActionAndACancellationAreAnnounced() {
        Payment action = pending();
        Payment canceled = pending();
        Payment canceledWithoutReason = pending();

        action.applyStripeStatus("requires_action", at(20), PaymentStatusSource.WEBHOOK, "evt_1");
        canceled.applyStripeStatus("canceled", at(20), PaymentStatusSource.WEBHOOK, "evt_2", "abandoned");
        canceledWithoutReason.applyStripeStatus("canceled", at(20), PaymentStatusSource.WEBHOOK, "evt_3", null);

        assertThat(action.pullDomainEvents())
                .containsExactly(new PaymentDomainEvent.ActionRequired(action.id(), action.orderId(), at(20)));
        assertThat(canceled.pullDomainEvents())
                .containsExactly(
                        new PaymentDomainEvent.Canceled(canceled.id(), canceled.orderId(), "abandoned", at(20)));
        assertThat(canceledWithoutReason.pullDomainEvents())
                .singleElement()
                .isInstanceOfSatisfying(
                        PaymentDomainEvent.Canceled.class,
                        event -> assertThat(event.reason()).isEqualTo("canceled"));
    }

    @Test
    void processingIsNotAnnounced() {
        Payment payment = pending();

        payment.applyStripeStatus("processing", at(20), PaymentStatusSource.WEBHOOK, "evt_1");

        assertThat(payment.status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(payment.pullDomainEvents()).isEmpty();
    }

    @Test
    @DisplayName("F09/F10: a repeated or stale report registers nothing")
    void repetitionsAndStaleReportsAreNotNews() {
        Payment payment = pending();
        payment.applyStripeStatus("succeeded", at(20), PaymentStatusSource.WEBHOOK, "evt_1");
        payment.pullDomainEvents();

        assertThat(payment.applyStripeStatus("succeeded", at(21), PaymentStatusSource.WEBHOOK, "evt_2"))
                .isEqualTo(StripeOutcome.UNCHANGED);
        assertThat(payment.applyStripeStatus("processing", at(22), PaymentStatusSource.WEBHOOK, "evt_3"))
                .isEqualTo(StripeOutcome.STALE_IGNORED);
        assertThat(payment.pullDomainEvents()).isEmpty();
    }

    @Test
    @DisplayName("§6.2: every failed attempt is news, also when the status does not move")
    void aFailedAttemptIsRecordedAndAnnounced() {
        Payment payment = pending();

        StripeOutcome outcome = payment.recordPaymentFailure(
                "requires_payment_method",
                "card_declined",
                "insufficient_funds",
                "Your card has insufficient funds.",
                at(20),
                PaymentStatusSource.WEBHOOK,
                "evt_1");

        assertThat(outcome).isEqualTo(StripeOutcome.UNCHANGED);
        assertThat(payment.lastErrorCode()).isEqualTo("card_declined");
        assertThat(payment.lastDeclineCode()).isEqualTo("insufficient_funds");
        assertThat(payment.lastErrorMessage()).isEqualTo("Your card has insufficient funds.");
        assertThat(payment.pullDomainEvents())
                .containsExactly(new PaymentDomainEvent.AttemptFailed(
                        payment.id(), payment.orderId(), "card_declined", "insufficient_funds", at(20)));
    }

    @Test
    void aFailedAuthenticationMovesBackToRequiresPaymentMethod() {
        Payment payment = inStatus(PaymentStatus.REQUIRES_ACTION);
        payment.pullDomainEvents();

        StripeOutcome outcome = payment.recordPaymentFailure(
                "requires_payment_method", null, " ", null, at(20), PaymentStatusSource.WEBHOOK, "evt_1");

        assertThat(outcome).isEqualTo(StripeOutcome.APPLIED);
        assertThat(payment.status()).isEqualTo(PaymentStatus.REQUIRES_PAYMENT_METHOD);
        assertThat(payment.pullDomainEvents())
                .containsExactly(new PaymentDomainEvent.AttemptFailed(
                        payment.id(), payment.orderId(), "payment_failed", null, at(20)));
    }

    @Test
    void aStaleFailureChangesNothing() {
        Payment payment = inStatus(PaymentStatus.SUCCEEDED);
        payment.pullDomainEvents();

        assertThat(payment.recordPaymentFailure(
                        "requires_payment_method",
                        "card_declined",
                        null,
                        "m",
                        at(20),
                        PaymentStatusSource.WEBHOOK,
                        "e"))
                .isEqualTo(StripeOutcome.STALE_IGNORED);
        assertThat(payment.lastErrorCode()).isNull();
        assertThat(payment.pullDomainEvents()).isEmpty();
    }

    @Test
    void aNewErrorWithoutDeclineCodeClearsTheOldOne() {
        Payment payment = pending();
        payment.recordPaymentFailure(
                "requires_payment_method",
                "card_declined",
                "lost_card",
                null,
                at(20),
                PaymentStatusSource.WEBHOOK,
                "e");

        payment.recordError("api_error", "Stripe is down", at(21));

        assertThat(payment.lastDeclineCode()).isNull();
    }

    @Test
    void aRefundIsAnnouncedWithItsIds() {
        Payment payment = inStatus(PaymentStatus.SUCCEEDED);
        payment.pullDomainEvents();

        assertThat(payment.markRefunded(REFUND_REQUEST, "re_1", at(20), PaymentStatusSource.WEBHOOK, "evt_1"))
                .isEqualTo(StripeOutcome.APPLIED);
        assertThat(payment.markRefunded(REFUND_REQUEST, "re_1", at(21), PaymentStatusSource.WEBHOOK, "evt_2"))
                .isEqualTo(StripeOutcome.UNCHANGED);

        assertThat(payment.pullDomainEvents())
                .containsExactly(new PaymentDomainEvent.Refunded(
                        payment.id(), payment.orderId(), REFUND_REQUEST, "re_1", payment.amount(), at(20)));
        assertThatThrownBy(() -> payment.markRefunded(REFUND_REQUEST, " ", at(22), PaymentStatusSource.WEBHOOK, "e"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFailedRefundLeavesThePaymentAndIsAnnounced() {
        Payment payment = inStatus(PaymentStatus.SUCCEEDED);
        payment.pullDomainEvents();

        payment.recordRefundFailure(REFUND_REQUEST, "expired_or_canceled_card", at(20));
        payment.recordRefundFailure(REFUND_REQUEST, null, at(21));

        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.pullDomainEvents())
                .containsExactly(
                        new PaymentDomainEvent.RefundFailed(
                                payment.id(), payment.orderId(), REFUND_REQUEST, "expired_or_canceled_card", at(20)),
                        new PaymentDomainEvent.RefundFailed(
                                payment.id(), payment.orderId(), REFUND_REQUEST, "unknown", at(21)));
    }

    @Test
    void aDisputeIsAnnouncedOnce() {
        Payment payment = inStatus(PaymentStatus.SUCCEEDED);
        payment.pullDomainEvents();

        assertThat(payment.markDisputed("dp_1", null, at(20))).isTrue();
        assertThat(payment.markDisputed("dp_1", "fraudulent", at(21))).isFalse();

        assertThat(payment.pullDomainEvents())
                .containsExactly(
                        new PaymentDomainEvent.Disputed(payment.id(), payment.orderId(), "dp_1", "general", at(20)));
        assertThatThrownBy(() -> payment.markDisputed(" ", "x", at(22))).isInstanceOf(IllegalArgumentException.class);
    }
}
