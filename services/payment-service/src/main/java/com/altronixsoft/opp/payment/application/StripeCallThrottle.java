package com.altronixsoft.opp.payment.application;

/**
 * Port: limits how fast a background job calls Stripe (architecture §8.4: reconciliation is rate-limited), so that a
 * large backlog never competes with the calls customers wait for.
 */
public interface StripeCallThrottle {

    /**
     * Waits for a permit, at most a short configured time.
     *
     * @return {@code false} if no permit came in time: the caller stops and leaves the rest for its next run
     */
    boolean tryAcquire();
}
