package com.altronixsoft.opp.contracts;

/** Kafka topic names (architecture §9.1). The suffix is the major version of the topic's contract. */
public final class Topics {

    /** Published by order-service, consumed by payment-service. Key: orderId. */
    public static final String ORDER_EVENTS = "order.events.v1";

    /** Published by payment-service, consumed by order-service. Key: orderId. */
    public static final String PAYMENT_EVENTS = "payment.events.v1";

    private Topics() {}
}
