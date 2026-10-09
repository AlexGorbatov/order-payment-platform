package com.altronixsoft.opp.payment.application;

import com.altronixsoft.opp.payment.domain.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * Creates the PaymentIntent of a payment. The idempotency key is derived from {@code paymentId}
 * ({@code pi-create:{paymentId}}), so every retry of the same payment is the same request and can never create a second
 * PaymentIntent; the provider remembers the key for about 24 hours.
 */
public record CreatePaymentIntentRequest(UUID paymentId, UUID orderId, Money amount) {

    public CreatePaymentIntentRequest {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(amount, "amount");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("a PaymentIntent needs a positive amount, got " + amount);
        }
    }
}
