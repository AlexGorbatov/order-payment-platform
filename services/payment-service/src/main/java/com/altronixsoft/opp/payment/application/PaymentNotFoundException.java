package com.altronixsoft.opp.payment.application;

import java.util.UUID;

/**
 * No payment for this order <em>for this caller</em>. A payment that belongs to somebody else is reported exactly like one
 * that does not exist (architecture §12). For a consumed order event it means the payment has not been created (yet).
 */
public class PaymentNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID orderId;

    public PaymentNotFoundException(UUID orderId) {
        super("No payment for order " + orderId);
        this.orderId = orderId;
    }

    public UUID orderId() {
        return orderId;
    }
}
