package com.altronixsoft.opp.payment.application;

import com.altronixsoft.opp.payment.domain.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * Refunds a payment in full. Idempotency key: {@code refund:{refundId}}, so a retry of the same refund can never refund
 * twice; a new refund (after a failed one) has a new id and so a new key.
 */
public record CreateRefundRequest(UUID refundId, UUID paymentId, String paymentIntentId, Money amount) {

    public CreateRefundRequest {
        Objects.requireNonNull(refundId, "refundId");
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(paymentIntentId, "paymentIntentId");
        Objects.requireNonNull(amount, "amount");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("a refund needs a positive amount, got " + amount);
        }
    }
}
