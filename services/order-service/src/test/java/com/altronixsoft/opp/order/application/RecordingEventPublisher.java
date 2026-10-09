package com.altronixsoft.opp.order.application;

import com.altronixsoft.opp.order.domain.OrderDomainEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** {@link OrderEventPublisher} that remembers what it was given, for the unit tests of the use cases. */
final class RecordingEventPublisher implements OrderEventPublisher {

    /** One call of {@link #publish}. */
    record Published(OrderDomainEvent event, UUID correlationId) {}

    final List<Published> published = new ArrayList<>();

    @Override
    public void publish(List<OrderDomainEvent> events, UUID correlationId) {
        events.forEach(event -> published.add(new Published(event, correlationId)));
    }

    List<OrderDomainEvent> events() {
        return published.stream().map(Published::event).toList();
    }
}
