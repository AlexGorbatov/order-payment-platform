package com.altronixsoft.opp.payment.adapter.out.stripe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.altronixsoft.opp.payment.StripeEvents;
import com.altronixsoft.opp.payment.application.StripeNotification;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The fields payment-service reads from each handled event type (docs.stripe.com/api/events/types). */
class StripeWebhookPayloadParserTest {

    private static final Instant CREATED = Instant.parse("2026-10-09T12:00:00Z");
    private static final UUID PAYMENT = UUID.fromString("0199e0a0-1111-7000-8000-000000000011");

    private final StripeWebhookPayloadParser parser = new StripeWebhookPayloadParser();

    private static String event(String type) {
        return StripeEvents.event(type)
                .created(CREATED)
                .paymentIntent("pi_parse_1")
                .payment(PAYMENT)
                .with("STRIPE_REFUND_ID", "re_parse_1")
                .with("REFUND_ID", PAYMENT.toString())
                .render();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "payment_intent.processing",
                "payment_intent.requires_action",
                "payment_intent.succeeded",
                "payment_intent.canceled",
                "payment_intent.payment_failed"
            })
    void paymentIntentEventsCarryIdStatusAndPaymentId(String type) {
        StripeNotification notification = parser.parse(type, event(type));

        assertThat(notification).isInstanceOfSatisfying(StripeNotification.PaymentIntentChanged.class, changed -> {
            assertThat(changed.paymentIntentId()).isEqualTo("pi_parse_1");
            assertThat(changed.paymentIdMetadata()).isEqualTo(PAYMENT.toString());
            assertThat(changed.status()).isNotBlank();
        });
    }

    @Test
    void aFailedPaymentCarriesItsSanitizedError() {
        StripeNotification notification =
                parser.parse("payment_intent.payment_failed", event("payment_intent.payment_failed"));

        assertThat(notification)
                .isEqualTo(new StripeNotification.PaymentIntentChanged(
                        "pi_parse_1",
                        PAYMENT.toString(),
                        "requires_payment_method",
                        null,
                        new StripeNotification.PaymentError(
                                "card_declined", "insufficient_funds", "Your card has insufficient funds.")));
    }

    @Test
    void aCancellationCarriesItsReason() {
        assertThat(parser.parse("payment_intent.canceled", event("payment_intent.canceled")))
                .isInstanceOfSatisfying(
                        StripeNotification.PaymentIntentChanged.class,
                        changed -> assertThat(changed.cancellationReason()).isEqualTo("requested_by_customer"));
    }

    @Test
    void refundsAndDisputesCarryTheirIds() {
        assertThat(parser.parse("charge.refunded", event("charge.refunded")))
                .isEqualTo(new StripeNotification.ChargeRefunded("pi_parse_1", true));
        assertThat(parser.parse("refund.failed", event("refund.failed")))
                .isEqualTo(new StripeNotification.RefundFailed(
                        "re_parse_1", "pi_parse_1", PAYMENT.toString(), "expired_or_canceled_card"));
        assertThat(parser.parse("charge.dispute.created", event("charge.dispute.created")))
                .isInstanceOfSatisfying(StripeNotification.DisputeCreated.class, dispute -> {
                    assertThat(dispute.disputeId()).startsWith("dp_synthetic_");
                    assertThat(dispute.paymentIntentId()).isEqualTo("pi_parse_1");
                    assertThat(dispute.reason()).isEqualTo("fraudulent");
                });
    }

    @Test
    void otherTypesAreUnsupported() {
        assertThat(parser.parse("customer.created", event("customer.created")))
                .isEqualTo(new StripeNotification.Unsupported("customer.created"));
    }

    @Test
    void aHandledTypeWithoutWhatIsNeededIsRejected() {
        assertThatThrownBy(() -> parser.parse("payment_intent.succeeded", "{\"data\":{\"object\":{\"status\":\"x\"}}}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("data.object.id");
        assertThatThrownBy(() -> parser.parse("charge.refunded", "{\"data\":{}}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("refund.failed", "not json"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void codesThatDoNotLookLikeStripeCodesAreDropped() {
        assertThat(StripeWebhookPayloadParser.code("card_declined")).isEqualTo("card_declined");
        assertThat(StripeWebhookPayloadParser.code("Card Declined")).isNull();
        assertThat(StripeWebhookPayloadParser.code("x".repeat(65))).isNull();
        assertThat(StripeWebhookPayloadParser.code(null)).isNull();
    }

    @Test
    void messagesLoseControlCharactersCardNumbersAndSecrets() {
        assertThat(StripeWebhookPayloadParser.message("Declined\n\t card 4242 4242 4242 4242 with sk_test_abcdef123"))
                .isEqualTo("Declined card [redacted] with sk_test_***");
        assertThat(StripeWebhookPayloadParser.message("x".repeat(2000))).hasSize(500);
        assertThat(StripeWebhookPayloadParser.message(" \n ")).isNull();
        assertThat(StripeWebhookPayloadParser.message(null)).isNull();
    }
}
