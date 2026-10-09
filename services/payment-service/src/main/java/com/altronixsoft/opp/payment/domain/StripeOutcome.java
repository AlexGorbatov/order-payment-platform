package com.altronixsoft.opp.payment.domain;

/** What happened to a status reported by Stripe (architecture §8.3, the ordering rule). */
public enum StripeOutcome {
    /** The payment moved to the reported status. */
    APPLIED,
    /**
     * The report is current and consistent but the payment already has that status, for example a second
     * {@code payment_failed} on a PaymentIntent that never left {@code requires_payment_method}. Nothing moved; the
     * ordering watermark did.
     */
    UNCHANGED,
    /**
     * The report is older than what was already applied, or the transition is not allowed from the current status
     * (a late {@code processing} after {@code succeeded}; anything after a terminal status). Not an error: the report
     * is dropped and recorded as such by the caller.
     */
    STALE_IGNORED
}
