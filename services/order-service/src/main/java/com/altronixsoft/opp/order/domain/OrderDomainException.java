package com.altronixsoft.opp.order.domain;

/** Base of the errors the order domain raises when a rule is violated. */
public abstract class OrderDomainException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    protected OrderDomainException(String message) {
        super(message);
    }
}
