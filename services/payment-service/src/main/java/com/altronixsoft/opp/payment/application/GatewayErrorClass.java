package com.altronixsoft.opp.payment.application;

/**
 * How a failed call to the payment provider is to be treated (architecture §8.2). The class decides what the caller does
 * next, so it is part of the port's contract.
 */
public enum GatewayErrorClass {
    /**
     * Connection problems, timeouts, 5xx, 429, a conflict with a request in flight. Nothing is known to be wrong with
     * the request itself: retry later with the same idempotency key (work-queue backoff, ADR-0008).
     */
    TRANSIENT,
    /** The provider rejected the request itself (invalid parameters, unknown object, declined card). Retrying cannot help. */
    PERMANENT,
    /** Our credentials or permissions are wrong (401/403). Alert; do not retry blindly. */
    CONFIG,
    /** An idempotency key was reused with different parameters. A bug in the platform: alert. */
    IDEMPOTENCY_MISMATCH
}
