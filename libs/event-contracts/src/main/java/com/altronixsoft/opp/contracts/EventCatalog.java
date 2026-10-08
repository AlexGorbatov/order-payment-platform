package com.altronixsoft.opp.contracts;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Registry of every event type this module knows. Deserialization dispatches on it; there is no default typing. */
public final class EventCatalog {

    private static final String ORDER = "Order";
    private static final String PAYMENT = "Payment";

    private static final List<EventDescriptor> ALL = List.of(
            order(EventTypes.ORDER_CREATED, OrderCreated.class),
            order(EventTypes.ORDER_CANCELLED, OrderCancelled.class),
            order(EventTypes.ORDER_REFUND_REQUESTED, OrderRefundRequested.class),
            payment(EventTypes.PAYMENT_INITIATED, PaymentInitiated.class),
            payment(EventTypes.PAYMENT_INITIATION_FAILED, PaymentInitiationFailed.class),
            payment(EventTypes.PAYMENT_ACTION_REQUIRED, PaymentActionRequired.class),
            payment(EventTypes.PAYMENT_ATTEMPT_FAILED, PaymentAttemptFailed.class),
            payment(EventTypes.PAYMENT_SUCCEEDED, PaymentSucceeded.class),
            payment(EventTypes.PAYMENT_CANCELED, PaymentCanceled.class),
            payment(EventTypes.PAYMENT_REFUNDED, PaymentRefunded.class),
            payment(EventTypes.PAYMENT_REFUND_FAILED, PaymentRefundFailed.class),
            payment(EventTypes.PAYMENT_DISPUTED, PaymentDisputed.class));

    private static final Map<String, EventDescriptor> BY_TYPE_AND_VERSION =
            ALL.stream().collect(Collectors.toUnmodifiableMap(d -> key(d.eventType(), d.eventVersion()), d -> d));

    private static final Map<Class<?>, EventDescriptor> BY_PAYLOAD_TYPE =
            ALL.stream().collect(Collectors.toUnmodifiableMap(EventDescriptor::payloadType, Function.identity()));

    private EventCatalog() {}

    /** All known event types, in documentation order. */
    public static List<EventDescriptor> all() {
        return ALL;
    }

    /** Looks up an event type at a specific version; empty for unknown types and for unknown versions of known types. */
    public static Optional<EventDescriptor> find(String eventType, int eventVersion) {
        return Optional.ofNullable(BY_TYPE_AND_VERSION.get(key(eventType, eventVersion)));
    }

    /** Descriptor of a payload class; every {@link DomainEvent} implementation is registered. */
    public static EventDescriptor of(Class<? extends DomainEvent> payloadType) {
        EventDescriptor descriptor = BY_PAYLOAD_TYPE.get(payloadType);
        if (descriptor == null) {
            throw new IllegalArgumentException("Payload type is not registered in EventCatalog: " + payloadType);
        }
        return descriptor;
    }

    private static String key(String eventType, int eventVersion) {
        return eventType + ":" + eventVersion;
    }

    private static EventDescriptor order(String eventType, Class<? extends OrderEvent> payloadType) {
        return new EventDescriptor(
                eventType, EventTypes.V1, ORDER, Producers.ORDER_SERVICE, Topics.ORDER_EVENTS, payloadType);
    }

    private static EventDescriptor payment(String eventType, Class<? extends PaymentEvent> payloadType) {
        return new EventDescriptor(
                eventType, EventTypes.V1, PAYMENT, Producers.PAYMENT_SERVICE, Topics.PAYMENT_EVENTS, payloadType);
    }
}
