package com.altronixsoft.opp.contracts;

import java.util.UUID;

/** A PaymentIntent was created at the provider for the order. */
public record PaymentInitiated(UUID paymentId, UUID orderId, String stripePaymentIntentId) implements PaymentEvent {

    public PaymentInitiated {
        Fields.require(paymentId, "paymentId");
        Fields.require(orderId, "orderId");
        Fields.requireText(stripePaymentIntentId, "stripePaymentIntentId");
    }
}
