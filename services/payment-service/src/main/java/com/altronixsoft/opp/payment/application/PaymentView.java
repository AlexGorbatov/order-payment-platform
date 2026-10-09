package com.altronixsoft.opp.payment.application;

import com.altronixsoft.opp.payment.domain.Payment;

/**
 * A payment as its owner (or an administrator) sees it.
 *
 * @param clientSecret the secret the browser needs to complete the payment at Stripe, or {@code null}. Fetched from Stripe on
 *     demand, only for the owner and only while the PaymentIntent still accepts a payment method or an action; never
 *     stored, never logged
 */
public record PaymentView(Payment payment, String clientSecret) {

    @Override
    public String toString() {
        return "PaymentView[payment=" + payment.id() + ", status=" + payment.status() + ", clientSecret="
                + (clientSecret == null ? "none" : "<redacted>") + "]";
    }
}
