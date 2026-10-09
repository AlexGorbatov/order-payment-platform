package com.altronixsoft.opp.payment.application;

/** What consuming one order event did. */
public enum OrderEventResult {
    /** A payment was created, due for PaymentIntent creation. */
    PAYMENT_CREATED,
    /** The order already has a payment (a replayed event); nothing changed. */
    PAYMENT_ALREADY_EXISTS,
    /** The payment had no PaymentIntent yet and is cancelled on the spot. */
    PAYMENT_CANCELED,
    /** The cancellation worker will cancel the PaymentIntent. */
    CANCEL_SCHEDULED,
    /** A cancellation is already scheduled or done. */
    CANCEL_ALREADY_PENDING,
    /** The payment is processing or finished: Stripe cannot cancel it; a late success is compensated by a refund (F18). */
    CANCEL_TOO_LATE,
    /** A refund was created, due for the refund worker. */
    REFUND_REQUESTED,
    /** The refund request was handled before (F22); nothing changed. */
    REFUND_ALREADY_REQUESTED
}
