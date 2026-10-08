package com.altronixsoft.opp.order.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Order lifecycle (architecture §5.1). The transition table is the single source of truth for what is allowed:
 *
 * <pre>
 * PENDING_PAYMENT → PAID | CANCELLED
 * PAID            → REFUND_REQUESTED
 * CANCELLED       → REFUND_REQUESTED          (a payment succeeded after the order was cancelled)
 * REFUND_REQUESTED→ REFUNDED | REFUND_FAILED
 * REFUND_FAILED   → REFUND_REQUESTED          (administrator retries)
 * REFUNDED        → (terminal)
 * </pre>
 *
 * {@code PaymentAttemptFailed} and {@code PaymentActionRequired} do not change the status; a dispute is a flag.
 */
public enum OrderStatus {
    PENDING_PAYMENT,
    PAID,
    CANCELLED,
    REFUND_REQUESTED,
    REFUNDED,
    REFUND_FAILED;

    private EnumSet<OrderStatus> allowedTargets;

    static {
        PENDING_PAYMENT.allowedTargets = EnumSet.of(PAID, CANCELLED);
        PAID.allowedTargets = EnumSet.of(REFUND_REQUESTED);
        CANCELLED.allowedTargets = EnumSet.of(REFUND_REQUESTED);
        REFUND_REQUESTED.allowedTargets = EnumSet.of(REFUNDED, REFUND_FAILED);
        REFUND_FAILED.allowedTargets = EnumSet.of(REFUND_REQUESTED);
        REFUNDED.allowedTargets = EnumSet.noneOf(OrderStatus.class);
    }

    /** The statuses an order in this status may move to. */
    public Set<OrderStatus> allowedTargets() {
        return EnumSet.copyOf(allowedTargets);
    }

    public boolean canTransitionTo(OrderStatus target) {
        return allowedTargets.contains(target);
    }

    /** No transition leads out of a terminal status. */
    public boolean isTerminal() {
        return allowedTargets.isEmpty();
    }
}
