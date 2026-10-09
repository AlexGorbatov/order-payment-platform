package com.altronixsoft.opp.payment.application;

/**
 * Storing the refund would break a uniqueness guarantee: the refund request was already handled
 * ({@code refund_refund_request_id_key}), the Stripe refund is already known, or the payment already has a refund that
 * has not failed ({@code refund_one_open_per_payment}).
 */
public class DuplicateRefundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String constraint;

    public DuplicateRefundException(String constraint, Throwable cause) {
        super("Duplicate refund (" + constraint + ")", cause);
        this.constraint = constraint;
    }

    public String constraint() {
        return constraint;
    }
}
