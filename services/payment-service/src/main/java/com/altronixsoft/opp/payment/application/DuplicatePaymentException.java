package com.altronixsoft.opp.payment.application;

/**
 * Storing the payment would break a uniqueness guarantee: there is already a payment for this order (the usual case of
 * a redelivered {@code OrderCreated}) or one for this PaymentIntent.
 */
public class DuplicatePaymentException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String constraint;

    public DuplicatePaymentException(String constraint, Throwable cause) {
        super("Duplicate payment (" + constraint + ")", cause);
        this.constraint = constraint;
    }

    /** The violated database constraint, for example {@code payment_order_id_key}. */
    public String constraint() {
        return constraint;
    }
}
