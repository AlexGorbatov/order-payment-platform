package com.altronixsoft.opp.contracts;

import java.util.UUID;

/** A refund succeeded at the provider. */
public record PaymentRefunded(
        UUID paymentId, UUID orderId, UUID refundRequestId, String stripeRefundId, long amountMinor)
        implements PaymentEvent {

    public PaymentRefunded {
        Fields.require(paymentId, "paymentId");
        Fields.require(orderId, "orderId");
        Fields.require(refundRequestId, "refundRequestId");
        Fields.requireText(stripeRefundId, "stripeRefundId");
        Currencies.requirePositive(amountMinor, "amountMinor");
    }
}
