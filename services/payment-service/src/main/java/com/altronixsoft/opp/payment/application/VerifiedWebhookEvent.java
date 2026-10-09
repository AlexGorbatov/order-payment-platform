package com.altronixsoft.opp.payment.application;

import java.time.Instant;
import java.util.Objects;

/**
 * The envelope of a webhook whose signature was verified.
 *
 * @param eventId Stripe's event id ({@code evt_...}); the deduplication key
 * @param type the event type, e.g. {@code payment_intent.succeeded}
 * @param apiVersion the API version the payload was rendered with; may be {@code null}
 * @param livemode whether the event comes from live mode; this platform accepts test mode only
 * @param createdAt the event's {@code created} (seconds precision): the ordering watermark of §8.3
 */
public record VerifiedWebhookEvent(
        String eventId, String type, String apiVersion, boolean livemode, Instant createdAt) {

    public VerifiedWebhookEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
