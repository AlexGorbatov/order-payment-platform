package com.altronixsoft.opp.payment.application;

import com.altronixsoft.opp.payment.domain.StripeWebhookEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Port: storage of received Stripe webhook events. */
public interface WebhookEventRepository {

    /**
     * Stores a received event unless one with the same id exists (Stripe redelivers).
     *
     * @return {@code true} if the event is new, {@code false} for a redelivery
     */
    boolean insertIfAbsent(StripeWebhookEvent event);

    Optional<StripeWebhookEvent> findById(String eventId);

    /** Stores the processing state of an event that exists. */
    void save(StripeWebhookEvent event);

    /**
     * Claims events to process: {@code RECEIVED} or {@code FAILED} with {@code nextAttemptAt <= now}, oldest first. Same
     * locking and lease contract as {@link PaymentRepository#claimDueBatch}.
     */
    List<StripeWebhookEvent> claimDueBatch(Instant now, int limit);
}
