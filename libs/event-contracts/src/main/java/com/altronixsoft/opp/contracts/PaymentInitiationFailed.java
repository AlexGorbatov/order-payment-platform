package com.altronixsoft.opp.contracts;

import java.util.UUID;

/** The PaymentIntent could not be created (permanent error or the idempotency window elapsed). */
public record PaymentInitiationFailed(UUID paymentId, UUID orderId, String errorCode) implements PaymentEvent {

    public PaymentInitiationFailed {
        Fields.require(paymentId, "paymentId");
        Fields.require(orderId, "orderId");
        Fields.requireText(errorCode, "errorCode");
    }
}
