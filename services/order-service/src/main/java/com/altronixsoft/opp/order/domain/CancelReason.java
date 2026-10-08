package com.altronixsoft.opp.order.domain;

/** Why an order was cancelled (architecture §9.3, {@code OrderCancelled}). */
public enum CancelReason {
    CUSTOMER,
    TIMEOUT,
    PAYMENT_INITIATION_FAILED,
    PAYMENT_CANCELED
}
