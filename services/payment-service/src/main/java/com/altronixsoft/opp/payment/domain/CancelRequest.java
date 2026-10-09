package com.altronixsoft.opp.payment.domain;

/** The result of asking a payment to cancel. */
public enum CancelRequest {
    /** No PaymentIntent existed yet; the payment is {@code CANCELED} right away. */
    CANCELED_LOCALLY,
    /** The PaymentIntent exists; the cancellation worker will cancel it at Stripe. */
    CANCEL_SCHEDULED,
    /** A cancellation is already scheduled or sent. */
    ALREADY_REQUESTED,
    /** The payment is already {@code CANCELED}. */
    ALREADY_CANCELED,
    /** Too late: the payment is processing or has reached another terminal status. Stripe cannot cancel it. */
    NOT_CANCELABLE
}
