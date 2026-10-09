package com.altronixsoft.opp.payment.application;

import com.altronixsoft.opp.payment.domain.StripeWebhookEvent;
import com.altronixsoft.opp.payment.domain.WebhookEventStatus;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** {@link WebhookEventRepository} in memory; copies on the way in and out, like a database would. */
final class InMemoryWebhookEvents implements WebhookEventRepository, FakeTransactions.Rollbackable {

    private Map<String, StripeWebhookEvent> stored = new LinkedHashMap<>();

    static StripeWebhookEvent copyOf(StripeWebhookEvent e) {
        return StripeWebhookEvent.restore(
                e.eventId(),
                e.type(),
                e.apiVersion(),
                e.livemode(),
                e.stripeCreatedAt(),
                e.payload(),
                e.status(),
                e.attempts(),
                e.nextAttemptAt(),
                e.lastError(),
                e.receivedAt(),
                e.processedAt());
    }

    StripeWebhookEvent get(String eventId) {
        return copyOf(stored.get(eventId));
    }

    @Override
    public boolean insertIfAbsent(StripeWebhookEvent event) {
        return stored.putIfAbsent(event.eventId(), copyOf(event)) == null;
    }

    @Override
    public Optional<StripeWebhookEvent> findById(String eventId) {
        return Optional.ofNullable(stored.get(eventId)).map(InMemoryWebhookEvents::copyOf);
    }

    @Override
    public void save(StripeWebhookEvent event) {
        if (!stored.containsKey(event.eventId())) {
            throw new IllegalStateException("never stored: " + event.eventId());
        }
        stored.put(event.eventId(), copyOf(event));
    }

    @Override
    public List<StripeWebhookEvent> claimDueBatch(Instant now, int limit) {
        return stored.values().stream()
                .filter(e -> e.status() == WebhookEventStatus.RECEIVED || e.status() == WebhookEventStatus.FAILED)
                .filter(e -> e.nextAttemptAt() != null && !e.nextAttemptAt().isAfter(now))
                .sorted(Comparator.comparing(StripeWebhookEvent::nextAttemptAt))
                .limit(limit)
                .map(InMemoryWebhookEvents::copyOf)
                .toList();
    }

    @Override
    public Object snapshot() {
        return new LinkedHashMap<>(stored);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void rollbackTo(Object snapshot) {
        stored = new LinkedHashMap<>((Map<String, StripeWebhookEvent>) snapshot);
    }
}
