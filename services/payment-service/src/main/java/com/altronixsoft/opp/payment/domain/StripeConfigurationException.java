package com.altronixsoft.opp.payment.domain;

/**
 * Stripe reports a state the platform is not configured for (manual capture). Retrying cannot help; somebody has to fix
 * the integration, so callers alert instead of retrying.
 */
public class StripeConfigurationException extends PaymentDomainException {

    private static final long serialVersionUID = 1L;

    public StripeConfigurationException(String message) {
        super(message);
    }
}
