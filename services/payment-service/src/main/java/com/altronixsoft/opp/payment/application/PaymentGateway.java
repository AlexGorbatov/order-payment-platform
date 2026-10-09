package com.altronixsoft.opp.payment.application;

/**
 * Port: the payment provider (Stripe), in the platform's own terms (architecture §8.2, ADR-0012).
 *
 * <p>Every method is a network call that can be slow or fail; none may run inside a database transaction (ADR-0008).
 * Every failure is a {@link PaymentGatewayException} whose {@link GatewayErrorClass} says what to do about it. Mutating
 * calls carry an idempotency key derived from local ids, so repeating a call after an ambiguous failure (a timeout)
 * is always safe.
 */
public interface PaymentGateway {

    /** Creates the PaymentIntent for a payment; idempotent per payment id. */
    GatewayPaymentIntent createPaymentIntent(CreatePaymentIntentRequest request);

    /** Reads the provider's current view of a PaymentIntent (reconciliation, showing the client secret). */
    GatewayPaymentIntent retrievePaymentIntent(String paymentIntentId);

    /** Cancels a PaymentIntent that has not been paid; idempotent per payment id. */
    GatewayPaymentIntent cancelPaymentIntent(CancelPaymentIntentRequest request);

    /** Creates a full refund; idempotent per refund id. */
    GatewayRefund createRefund(CreateRefundRequest request);

    /**
     * Test support only: confirms a PaymentIntent with a test payment method, as the browser would. Never called by
     * production flows.
     */
    GatewayPaymentIntent confirmPaymentIntentForTest(ConfirmPaymentIntentRequest request);
}
