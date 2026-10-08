package com.altronixsoft.opp.order.application;

/** The caller's role does not allow the operation (the web layer normally stops this earlier; this is the backstop). */
public class OperationNotPermittedException extends RuntimeException {

    public OperationNotPermittedException(String message) {
        super(message);
    }
}
