package com.altronixsoft.opp.payment.domain;

import java.util.UUID;

/** The command is not allowed from the refund's current status (architecture §5.3). */
public class IllegalRefundTransitionException extends PaymentDomainException {

    private static final long serialVersionUID = 1L;

    private final UUID refundId;
    private final RefundStatus from;
    private final String action;

    public IllegalRefundTransitionException(UUID refundId, RefundStatus from, String action) {
        super("Refund " + refundId + " is " + from + " and cannot " + action);
        this.refundId = refundId;
        this.from = from;
        this.action = action;
    }

    public UUID refundId() {
        return refundId;
    }

    public RefundStatus from() {
        return from;
    }

    public String action() {
        return action;
    }
}
