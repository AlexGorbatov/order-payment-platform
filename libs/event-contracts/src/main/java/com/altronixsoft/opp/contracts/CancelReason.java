package com.altronixsoft.opp.contracts;

/** Why an order was cancelled ({@link OrderCancelled}). */
public enum CancelReason {
    /** The customer cancelled while the order was awaiting payment. */
    CUSTOMER,
    /** The payment window expired. */
    TIMEOUT,
    /** The payment could not be initiated at the provider. */
    PAYMENT_INITIATION_FAILED,
    /** The payment was cancelled at the provider. */
    PAYMENT_CANCELED
}
