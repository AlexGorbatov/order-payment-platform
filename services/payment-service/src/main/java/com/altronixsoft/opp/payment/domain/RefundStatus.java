package com.altronixsoft.opp.payment.domain;

import java.util.EnumSet;
import java.util.Set;

/** Status of a {@link Refund} (architecture §5.3): {@code REQUESTED → PENDING → SUCCEEDED | FAILED}. */
public enum RefundStatus {
    REQUESTED,
    PENDING,
    SUCCEEDED,
    FAILED;

    private EnumSet<RefundStatus> allowedTargets;

    static {
        // REQUESTED → FAILED: Stripe refused to create the refund at all
        REQUESTED.allowedTargets = EnumSet.of(PENDING, FAILED);
        PENDING.allowedTargets = EnumSet.of(SUCCEEDED, FAILED);
        SUCCEEDED.allowedTargets = EnumSet.noneOf(RefundStatus.class);
        FAILED.allowedTargets = EnumSet.noneOf(RefundStatus.class);
    }

    public Set<RefundStatus> allowedTargets() {
        return EnumSet.copyOf(allowedTargets);
    }

    public boolean canTransitionTo(RefundStatus target) {
        return allowedTargets.contains(target);
    }

    public boolean isTerminal() {
        return allowedTargets.isEmpty();
    }
}
