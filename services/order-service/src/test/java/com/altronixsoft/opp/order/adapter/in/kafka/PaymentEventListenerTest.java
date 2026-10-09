package com.altronixsoft.opp.order.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.altronixsoft.opp.contracts.PaymentActionRequired;
import com.altronixsoft.opp.contracts.PaymentAttemptFailed;
import com.altronixsoft.opp.contracts.PaymentCanceled;
import com.altronixsoft.opp.contracts.PaymentDisputed;
import com.altronixsoft.opp.contracts.PaymentInitiated;
import com.altronixsoft.opp.contracts.PaymentInitiationFailed;
import com.altronixsoft.opp.contracts.PaymentRefundFailed;
import com.altronixsoft.opp.contracts.PaymentRefunded;
import com.altronixsoft.opp.contracts.PaymentSucceeded;
import com.altronixsoft.opp.order.application.PaymentEventCommand.PaymentOutcome;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Wire contracts of payment events to the use-case outcomes. */
class PaymentEventListenerTest {

    private static final UUID PAYMENT = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID REFUND = UUID.randomUUID();

    @Test
    void everyPaymentEventMapsToItsOutcome() {
        assertThat(PaymentEventListener.toOutcome(new PaymentSucceeded(
                        PAYMENT, ORDER, 3097, "EUR", "pi_1", Instant.parse("2026-10-08T12:00:00Z"))))
                .contains(new PaymentOutcome.Succeeded());
        assertThat(PaymentEventListener.toOutcome(new PaymentInitiationFailed(PAYMENT, ORDER, "invalid_request")))
                .contains(new PaymentOutcome.InitiationFailed("invalid_request"));
        assertThat(PaymentEventListener.toOutcome(new PaymentCanceled(PAYMENT, ORDER, "abandoned")))
                .contains(new PaymentOutcome.Canceled("abandoned"));
        assertThat(PaymentEventListener.toOutcome(new PaymentAttemptFailed(PAYMENT, ORDER, "card_declined", null)))
                .contains(new PaymentOutcome.AttemptFailed("card_declined", null));
        assertThat(PaymentEventListener.toOutcome(new PaymentActionRequired(PAYMENT, ORDER)))
                .contains(new PaymentOutcome.ActionRequired());
        assertThat(PaymentEventListener.toOutcome(new PaymentRefunded(PAYMENT, ORDER, REFUND, "re_1", 3097)))
                .contains(new PaymentOutcome.Refunded(REFUND));
        assertThat(PaymentEventListener.toOutcome(new PaymentRefundFailed(PAYMENT, ORDER, REFUND, "card_closed")))
                .contains(new PaymentOutcome.RefundFailed(REFUND, "card_closed"));
        assertThat(PaymentEventListener.toOutcome(new PaymentDisputed(PAYMENT, ORDER, "dp_1", "fraudulent")))
                .contains(new PaymentOutcome.Disputed("dp_1"));
    }

    @Test
    void paymentInitiatedNeedsNoReaction() {
        assertThat(PaymentEventListener.toOutcome(new PaymentInitiated(PAYMENT, ORDER, "pi_1")))
                .isEmpty();
    }
}
