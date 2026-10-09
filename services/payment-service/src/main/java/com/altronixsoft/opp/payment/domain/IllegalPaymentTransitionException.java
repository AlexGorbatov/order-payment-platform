package com.altronixsoft.opp.payment.domain;

import java.util.UUID;

/**
 * One of our own commands is not allowed from the payment's current status. (A status <em>reported by Stripe</em> that
 * does not fit is not an error; see {@link StripeOutcome#STALE_IGNORED}.)
 */
public class IllegalPaymentTransitionException extends PaymentDomainException {

    private static final long serialVersionUID = 1L;

    private final UUID paymentId;
    private final PaymentStatus from;
    private final String action;

    public IllegalPaymentTransitionException(UUID paymentId, PaymentStatus from, String action) {
        super("Payment " + paymentId + " is " + from + " and cannot " + action);
        this.paymentId = paymentId;
        this.from = from;
        this.action = action;
    }

    public UUID paymentId() {
        return paymentId;
    }

    public PaymentStatus from() {
        return from;
    }

    public String action() {
        return action;
    }
}
