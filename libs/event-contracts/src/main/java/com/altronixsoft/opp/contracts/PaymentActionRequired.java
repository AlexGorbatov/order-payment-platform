package com.altronixsoft.opp.contracts;

import java.util.UUID;

/** The customer must complete an additional step (for example 3-D Secure) before the payment can proceed. */
public record PaymentActionRequired(UUID paymentId, UUID orderId) implements PaymentEvent {

    public PaymentActionRequired {
        Fields.require(paymentId, "paymentId");
        Fields.require(orderId, "orderId");
    }
}
