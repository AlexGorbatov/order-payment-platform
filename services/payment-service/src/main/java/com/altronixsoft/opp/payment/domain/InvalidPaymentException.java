package com.altronixsoft.opp.payment.domain;

/** A payment, refund or webhook event cannot be created with these values. */
public class InvalidPaymentException extends PaymentDomainException {

    private static final long serialVersionUID = 1L;

    public InvalidPaymentException(String message) {
        super(message);
    }
}
