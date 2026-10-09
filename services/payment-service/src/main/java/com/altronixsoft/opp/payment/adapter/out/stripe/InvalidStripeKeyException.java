package com.altronixsoft.opp.payment.adapter.out.stripe;

/** The configured Stripe API key is missing or is not a test-mode key; the application refuses to start. */
public class InvalidStripeKeyException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /** Why the key was refused. */
    public enum Reason {
        MISSING,
        NOT_A_TEST_KEY
    }

    private final Reason reason;
    private final String shownPrefix;

    InvalidStripeKeyException(Reason reason, String shownPrefix, String message) {
        super(message);
        this.reason = reason;
        this.shownPrefix = shownPrefix;
    }

    public Reason reason() {
        return reason;
    }

    /** The only part of the offending key that is ever shown, for example {@code sk_live_}; never the secret. */
    public String shownPrefix() {
        return shownPrefix;
    }
}
