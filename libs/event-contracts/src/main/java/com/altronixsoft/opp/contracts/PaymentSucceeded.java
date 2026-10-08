package com.altronixsoft.opp.contracts;

import java.time.Instant;
import java.util.UUID;

/** The payment succeeded; the order can be marked paid. */
public record PaymentSucceeded(
        UUID paymentId,
        UUID orderId,
        long amountMinor,
        String currency,
        String stripePaymentIntentId,
        Instant succeededAt)
        implements PaymentEvent {

    public PaymentSucceeded {
        Fields.require(paymentId, "paymentId");
        Fields.require(orderId, "orderId");
        Currencies.requirePositive(amountMinor, "amountMinor");
        Currencies.requireIso4217(currency);
        Fields.requireText(stripePaymentIntentId, "stripePaymentIntentId");
        Fields.require(succeededAt, "succeededAt");
    }
}
