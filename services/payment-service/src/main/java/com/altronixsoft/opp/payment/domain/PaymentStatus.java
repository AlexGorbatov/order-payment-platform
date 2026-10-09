package com.altronixsoft.opp.payment.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Status of a {@link Payment} (architecture §5.2). The table of allowed transitions is the state diagram: nothing leaves
 * a terminal status except {@code SUCCEEDED → REFUNDED}.
 */
public enum PaymentStatus {
    CREATED,
    REQUIRES_PAYMENT_METHOD,
    REQUIRES_ACTION,
    PROCESSING,
    SUCCEEDED,
    CANCELED,
    INITIATION_FAILED,
    REFUNDED;

    private EnumSet<PaymentStatus> allowedTargets;

    static {
        CREATED.allowedTargets = EnumSet.of(REQUIRES_PAYMENT_METHOD, INITIATION_FAILED, CANCELED);
        REQUIRES_PAYMENT_METHOD.allowedTargets = EnumSet.of(REQUIRES_ACTION, PROCESSING, SUCCEEDED, CANCELED);
        REQUIRES_ACTION.allowedTargets = EnumSet.of(PROCESSING, SUCCEEDED, REQUIRES_PAYMENT_METHOD, CANCELED);
        PROCESSING.allowedTargets = EnumSet.of(SUCCEEDED, REQUIRES_PAYMENT_METHOD);
        SUCCEEDED.allowedTargets = EnumSet.of(REFUNDED);
        CANCELED.allowedTargets = EnumSet.noneOf(PaymentStatus.class);
        INITIATION_FAILED.allowedTargets = EnumSet.noneOf(PaymentStatus.class);
        REFUNDED.allowedTargets = EnumSet.noneOf(PaymentStatus.class);
    }

    /** The statuses a payment in this status may move to. */
    public Set<PaymentStatus> allowedTargets() {
        return EnumSet.copyOf(allowedTargets);
    }

    public boolean canTransitionTo(PaymentStatus target) {
        return allowedTargets.contains(target);
    }

    /** No transition leads out, or only the refund one does ({@code SUCCEEDED}). */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == CANCELED || this == INITIATION_FAILED || this == REFUNDED;
    }

    /** Stripe can still cancel the PaymentIntent in these statuses. */
    public boolean isCancelableAtStripe() {
        return this == REQUIRES_PAYMENT_METHOD || this == REQUIRES_ACTION;
    }
}
