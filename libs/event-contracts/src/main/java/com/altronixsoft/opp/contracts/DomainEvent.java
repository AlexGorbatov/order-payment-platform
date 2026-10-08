package com.altronixsoft.opp.contracts;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.UUID;

/**
 * Payload of a platform event (architecture §9.3). The permitted subtypes are the two event families, one per
 * producing service; the concrete payload records are the permitted subtypes of those.
 */
public sealed interface DomainEvent permits OrderEvent, PaymentEvent {

    /** The order this event belongs to. It is also the Kafka partition key, so all events of an order stay ordered. */
    UUID orderId();

    /** Id of the aggregate that emitted the event (envelope {@code aggregateId}). */
    @JsonIgnore
    UUID aggregateId();
}
