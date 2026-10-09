package com.altronixsoft.opp.payment.adapter.out.persistence;

import com.altronixsoft.opp.payment.application.WebhookEventRepository;
import com.altronixsoft.opp.payment.domain.StripeWebhookEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link WebhookEventRepository} on JPA: a stored event is inserted once and only its processing state changes. */
@Repository
@Transactional
class JpaWebhookEventRepository implements WebhookEventRepository {

    private final StripeWebhookEventJpaRepository events;

    JpaWebhookEventRepository(StripeWebhookEventJpaRepository events) {
        this.events = events;
    }

    @Override
    public boolean insertIfAbsent(StripeWebhookEvent event) {
        return events.insertIfAbsent(
                        event.eventId(),
                        event.type(),
                        event.apiVersion(),
                        event.livemode(),
                        event.stripeCreatedAt(),
                        event.payload(),
                        event.status().name(),
                        event.attempts(),
                        event.nextAttemptAt(),
                        event.lastError(),
                        event.receivedAt(),
                        event.processedAt())
                == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StripeWebhookEvent> findById(String eventId) {
        return events.findById(eventId).map(PaymentMapper::toDomain);
    }

    @Override
    public void save(StripeWebhookEvent event) {
        StripeWebhookEventEntity entity = events.findById(event.eventId())
                .orElseThrow(() -> new IllegalStateException("Webhook event " + event.eventId() + " was never stored"));
        PaymentMapper.applyChanges(event, entity);
        events.saveAndFlush(entity);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<StripeWebhookEvent> claimDueBatch(Instant now, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        return events.claimDue(now, limit).stream().map(PaymentMapper::toDomain).toList();
    }
}
