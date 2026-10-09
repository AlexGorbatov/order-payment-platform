package com.altronixsoft.opp.payment.application;

import com.altronixsoft.opp.payment.domain.StripeWebhookEvent;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;

/**
 * Use case: the webhook endpoint (architecture §6.6, ADR-0009). Verify, persist, acknowledge — nothing else: the event is
 * processed later by {@link ProcessWebhookEventsService}, so the answer to Stripe is fast and does not depend on the
 * business logic.
 *
 * <ol>
 *   <li>The signature is checked against the raw body; a bad or old one is refused and nothing is stored (F12).
 *   <li>A live-mode event is refused (F13).
 *   <li>The event is inserted with {@code ON CONFLICT DO NOTHING}: a redelivery is acknowledged and has no effect (F09).
 * </ol>
 */
@Service
public class ReceiveWebhookService {

    private final WebhookVerifier verifier;
    private final WebhookEventRepository events;
    private final Clock clock;

    public ReceiveWebhookService(WebhookVerifier verifier, WebhookEventRepository events, Clock clock) {
        this.verifier = verifier;
        this.events = events;
        this.clock = clock;
    }

    /** What happened to an accepted webhook. */
    public record Received(String eventId, String type, boolean duplicate) {}

    /**
     * @throws InvalidWebhookException the request is not a genuine, fresh Stripe event
     * @throws LiveModeWebhookException the event is from live mode
     */
    public Received receive(String payload, String signatureHeader) {
        VerifiedWebhookEvent verified = verifier.verify(payload, signatureHeader);
        if (verified.livemode()) {
            throw new LiveModeWebhookException(verified.eventId(), verified.type());
        }
        StripeWebhookEvent event = StripeWebhookEvent.receive(
                verified.eventId(),
                verified.type(),
                verified.apiVersion(),
                false,
                verified.createdAt(),
                payload,
                clock.instant().truncatedTo(ChronoUnit.MICROS));
        boolean inserted = events.insertIfAbsent(event);
        return new Received(verified.eventId(), verified.type(), !inserted);
    }
}
