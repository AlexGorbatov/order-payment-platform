package com.altronixsoft.opp.payment.domain;

/** Stripe reported a PaymentIntent status this version of the platform does not know. */
public class UnknownStripeStatusException extends PaymentDomainException {

    private static final long serialVersionUID = 1L;

    private final String stripeStatus;

    public UnknownStripeStatusException(String stripeStatus) {
        super("Unknown PaymentIntent status: " + stripeStatus);
        this.stripeStatus = stripeStatus;
    }

    public String stripeStatus() {
        return stripeStatus;
    }
}
