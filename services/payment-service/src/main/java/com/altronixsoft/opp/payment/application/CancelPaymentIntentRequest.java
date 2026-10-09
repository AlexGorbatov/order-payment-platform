package com.altronixsoft.opp.payment.application;

import java.util.Objects;
import java.util.UUID;

/** Cancels a PaymentIntent. Idempotency key: {@code pi-cancel:{paymentId}}. */
public record CancelPaymentIntentRequest(UUID paymentId, String paymentIntentId, Reason reason) {

    /** Why the PaymentIntent is cancelled (the provider records it). */
    public enum Reason {
        /** The customer cancelled the order. */
        REQUESTED_BY_CUSTOMER,
        /** Nobody paid in time. */
        ABANDONED
    }

    public CancelPaymentIntentRequest {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(paymentIntentId, "paymentIntentId");
        Objects.requireNonNull(reason, "reason");
    }
}
