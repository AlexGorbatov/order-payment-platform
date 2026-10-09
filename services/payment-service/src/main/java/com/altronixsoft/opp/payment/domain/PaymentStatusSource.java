package com.altronixsoft.opp.payment.domain;

/** Where a status change came from (column {@code payment_status_history.source}). */
public enum PaymentStatusSource {
    /** The response to one of our own Stripe API calls. */
    STRIPE_API,
    /** A webhook event. */
    WEBHOOK,
    /** The reconciliation job comparing us with Stripe. */
    RECONCILIATION,
    /** A decision taken inside the service (cancel before a PaymentIntent exists, initiation failed). */
    LOCAL;

    /** Whether the change reports what Stripe says, and so is subject to the event-ordering rule (§8.3). */
    public boolean isFromStripe() {
        return this != LOCAL;
    }
}
