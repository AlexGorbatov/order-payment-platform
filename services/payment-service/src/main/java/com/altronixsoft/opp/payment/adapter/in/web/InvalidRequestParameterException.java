package com.altronixsoft.opp.payment.adapter.in.web;

/** A request parameter has a value the API does not accept; answered as a {@code validation-failed} problem. */
class InvalidRequestParameterException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String field;

    InvalidRequestParameterException(String field, String message) {
        super(message);
        this.field = field;
    }

    String field() {
        return field;
    }
}
