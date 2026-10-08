package com.altronixsoft.opp.contracts;

import java.util.UUID;

/** A refund of a paid order was requested; {@code refundRequestId} makes the request idempotent end to end. */
public record OrderRefundRequested(
        UUID orderId, UUID refundRequestId, long amountMinor, String currency, RefundReason reason)
        implements OrderEvent {

    public OrderRefundRequested {
        Fields.require(orderId, "orderId");
        Fields.require(refundRequestId, "refundRequestId");
        Currencies.requirePositive(amountMinor, "amountMinor");
        Currencies.requireIso4217(currency);
        Fields.require(reason, "reason");
    }
}
