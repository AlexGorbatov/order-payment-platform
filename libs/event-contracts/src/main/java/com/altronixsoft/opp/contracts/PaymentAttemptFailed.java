package com.altronixsoft.opp.contracts;

import java.util.UUID;

/** A payment attempt failed (for example a declined card); the customer may retry with another payment method. */
public record PaymentAttemptFailed(UUID paymentId, UUID orderId, String errorCode, String declineCode)
        implements PaymentEvent {

    /** {@code declineCode} is optional (null when the provider gave none) and must already be sanitized. */
    public PaymentAttemptFailed {
        Fields.require(paymentId, "paymentId");
        Fields.require(orderId, "orderId");
        Fields.requireText(errorCode, "errorCode");
        if (declineCode != null && declineCode.isBlank()) {
            throw new IllegalArgumentException("declineCode must not be blank when present");
        }
    }
}
