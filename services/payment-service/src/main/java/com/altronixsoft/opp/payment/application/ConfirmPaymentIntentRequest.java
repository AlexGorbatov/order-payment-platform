package com.altronixsoft.opp.payment.application;

import java.util.Objects;
import java.util.UUID;

/**
 * Confirms a PaymentIntent with a test payment method, standing in for the browser (architecture §8.5; only the
 * test-support endpoint uses it). The same PaymentIntent may be confirmed again with another card after a decline, so
 * each attempt has its own id and so its own idempotency key ({@code pi-confirm:{paymentId}:{attemptId}}).
 *
 * @param paymentMethodId a test payment method such as {@code pm_card_visa}
 */
public record ConfirmPaymentIntentRequest(
        UUID paymentId, String paymentIntentId, String paymentMethodId, UUID attemptId) {

    public ConfirmPaymentIntentRequest {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(paymentIntentId, "paymentIntentId");
        Objects.requireNonNull(paymentMethodId, "paymentMethodId");
        Objects.requireNonNull(attemptId, "attemptId");
    }
}
