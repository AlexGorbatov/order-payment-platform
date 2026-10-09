package com.altronixsoft.opp.payment.domain;

/** Base of the failures raised by the payment domain. */
public abstract class PaymentDomainException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    protected PaymentDomainException(String message) {
        super(message);
    }
}
