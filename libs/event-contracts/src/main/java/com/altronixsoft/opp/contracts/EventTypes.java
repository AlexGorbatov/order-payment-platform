package com.altronixsoft.opp.contracts;

/**
 * Event type names (envelope {@code eventType}, Kafka header {@code eventType}) and the current schema version.
 * Names are part of the wire contract and never change.
 */
public final class EventTypes {

    /** Version of every event type at the time of writing; a breaking change of one type bumps only that type. */
    public static final int V1 = 1;

    public static final String ORDER_CREATED = "OrderCreated";
    public static final String ORDER_CANCELLED = "OrderCancelled";
    public static final String ORDER_REFUND_REQUESTED = "OrderRefundRequested";

    public static final String PAYMENT_INITIATED = "PaymentInitiated";
    public static final String PAYMENT_INITIATION_FAILED = "PaymentInitiationFailed";
    public static final String PAYMENT_ACTION_REQUIRED = "PaymentActionRequired";
    public static final String PAYMENT_ATTEMPT_FAILED = "PaymentAttemptFailed";
    public static final String PAYMENT_SUCCEEDED = "PaymentSucceeded";
    public static final String PAYMENT_CANCELED = "PaymentCanceled";
    public static final String PAYMENT_REFUNDED = "PaymentRefunded";
    public static final String PAYMENT_REFUND_FAILED = "PaymentRefundFailed";
    public static final String PAYMENT_DISPUTED = "PaymentDisputed";

    private EventTypes() {}
}
