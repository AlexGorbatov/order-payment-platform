package com.altronixsoft.opp.contracts;

import java.util.UUID;

/** The customer opened a dispute (chargeback) for the payment. */
public record PaymentDisputed(UUID paymentId, UUID orderId, String disputeId, String reason) implements PaymentEvent {

    public PaymentDisputed {
        Fields.require(paymentId, "paymentId");
        Fields.require(orderId, "orderId");
        Fields.requireText(disputeId, "disputeId");
        Fields.requireText(reason, "reason");
    }
}
