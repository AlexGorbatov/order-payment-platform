package com.altronixsoft.opp.payment.adapter.out.stripe;

import java.util.UUID;

/**
 * The Stripe idempotency keys (architecture §7.3, §8.2), derived from local ids so that a retry of the same operation is
 * always the same request to Stripe. Stripe accepts keys of up to 255 characters and forgets them after (at least) 24
 * hours, which is why a payment still {@code CREATED} after 23 hours is failed instead of retried.
 */
public final class IdempotencyKeys {

    private IdempotencyKeys() {}

    public static String paymentIntentCreate(UUID paymentId) {
        return "pi-create:" + paymentId;
    }

    public static String paymentIntentCancel(UUID paymentId) {
        return "pi-cancel:" + paymentId;
    }

    public static String refund(UUID refundId) {
        return "refund:" + refundId;
    }

    public static String paymentIntentConfirm(UUID paymentId, UUID attemptId) {
        return "pi-confirm:" + paymentId + ":" + attemptId;
    }
}
