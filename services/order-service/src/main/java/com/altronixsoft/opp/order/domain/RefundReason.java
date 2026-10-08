package com.altronixsoft.opp.order.domain;

/** Why a refund was requested (architecture §9.3, {@code OrderRefundRequested}). */
public enum RefundReason {
    /** An administrator refunds a paid order, or retries a failed refund. */
    ADMIN,
    /** A payment succeeded after the order had already been cancelled: automatic compensation. */
    LATE_PAYMENT_AFTER_CANCEL
}
