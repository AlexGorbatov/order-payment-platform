package com.altronixsoft.opp.order.application;

/** How {@link ApplyPaymentEventService} dealt with a payment event. */
public enum PaymentEventResult {
    /** The order changed as the event says (a status change, a history note or the dispute flag). */
    APPLIED,
    /** The payment succeeded after the order had been cancelled: an automatic refund was requested (F18). */
    COMPENSATED,
    /**
     * Nothing to do: the event is a late duplicate or arrived after the order had moved on (for example a payment
     * cancellation for an order the customer cancelled first). Not an error.
     */
    IGNORED
}
