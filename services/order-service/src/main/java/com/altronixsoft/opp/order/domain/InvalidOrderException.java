package com.altronixsoft.opp.order.domain;

/** The data of a new order breaks an invariant: line count, quantity, currency, duplicates, price. */
public class InvalidOrderException extends OrderDomainException {

    private static final long serialVersionUID = 1L;

    public InvalidOrderException(String message) {
        super(message);
    }
}
