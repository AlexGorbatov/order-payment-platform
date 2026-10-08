package com.altronixsoft.opp.contracts;

import java.util.UUID;

/** A refund failed at the provider; an administrator can retry it. */
public record PaymentRefundFailed(UUID paymentId, UUID orderId, UUID refundRequestId, String failureReason)
        implements PaymentEvent {

    public PaymentRefundFailed {
        Fields.require(paymentId, "paymentId");
        Fields.require(orderId, "orderId");
        Fields.require(refundRequestId, "refundRequestId");
        Fields.requireText(failureReason, "failureReason");
    }
}
