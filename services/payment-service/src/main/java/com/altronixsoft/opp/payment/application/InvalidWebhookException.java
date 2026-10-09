package com.altronixsoft.opp.payment.application;

/**
 * A webhook request that must not be trusted (F12): nothing of it is stored. The message never contains the payload or
 * the signature.
 */
public class InvalidWebhookException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why the request was refused; becomes the {@code reason} tag of {@code webhook.signature.failures}. */
    public enum Reason {
        /** No {@code Stripe-Signature} header. */
        MISSING_SIGNATURE,
        /** No signature matches a configured secret, or the timestamp is outside the tolerance (replay). */
        INVALID_SIGNATURE,
        /** Correctly signed, but not a Stripe event. */
        MALFORMED_PAYLOAD,
        /** No signing secret is configured: every webhook is refused. */
        NOT_CONFIGURED
    }

    private final Reason reason;

    public InvalidWebhookException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
