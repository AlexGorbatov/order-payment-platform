package com.altronixsoft.opp.order.domain;

/** What caused a status change; recorded in the order status history. */
public enum TransitionSource {
    /** A customer or administrator call. */
    API,
    /** A consumed event, identified by its event id. */
    EVENT,
    /** A scheduled job, for example the payment timeout. */
    JOB
}
