package com.altronixsoft.opp.contracts;

/** Why a refund was requested ({@link OrderRefundRequested}). */
public enum RefundReason {
    /** An administrator refunded a paid order. */
    ADMIN,
    /** The payment succeeded after the order had already been cancelled. */
    LATE_PAYMENT_AFTER_CANCEL
}
