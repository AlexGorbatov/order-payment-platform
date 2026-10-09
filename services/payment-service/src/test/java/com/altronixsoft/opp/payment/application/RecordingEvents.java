package com.altronixsoft.opp.payment.application;

import com.altronixsoft.opp.payment.domain.PaymentDomainEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Remembers what was published. */
final class RecordingEvents implements PaymentEventPublisher {

    record Published(PaymentDomainEvent event, UUID correlationId, UUID causationId) {}

    final List<Published> published = new ArrayList<>();
    RuntimeException failure;

    @Override
    public void publish(List<PaymentDomainEvent> events, UUID correlationId, UUID causationId) {
        if (failure != null) {
            throw failure;
        }
        events.forEach(e -> published.add(new Published(e, correlationId, causationId)));
    }
}
