package com.altronixsoft.opp.payment.application;

import com.altronixsoft.opp.payment.domain.PaymentStatus;
import java.util.UUID;

/** The payment cannot be confirmed now: it has no PaymentIntent yet, or is not waiting for a payment method or action. */
public class PaymentNotConfirmableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID orderId;
    private final PaymentStatus status;

    public PaymentNotConfirmableException(UUID orderId, PaymentStatus status) {
        super("The payment of order " + orderId + " is " + status + " and cannot be confirmed");
        this.orderId = orderId;
        this.status = status;
    }

    public UUID orderId() {
        return orderId;
    }

    public PaymentStatus status() {
        return status;
    }
}
