package com.altronixsoft.opp.contracts;

import java.util.UUID;

/** The payment was cancelled at the provider; no money was captured. */
public record PaymentCanceled(UUID paymentId, UUID orderId, String reason) implements PaymentEvent {

    public PaymentCanceled {
        Fields.require(paymentId, "paymentId");
        Fields.require(orderId, "orderId");
        Fields.requireText(reason, "reason");
    }
}
