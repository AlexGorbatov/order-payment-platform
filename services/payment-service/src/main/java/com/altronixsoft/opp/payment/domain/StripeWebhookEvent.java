package com.altronixsoft.opp.payment.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * A Stripe webhook event as it is stored before it is processed (architecture §6.6): persist first, acknowledge, process
 * later from the work queue. The event id is the primary key, so a redelivery is stored once.
 */
public final class StripeWebhookEvent {

    private final String eventId;
    private final String type;
    private final String apiVersion;
    private final boolean livemode;
    private final Instant stripeCreatedAt;
    private final String payload;
    private final Instant receivedAt;

    private WebhookEventStatus status;
    private int attempts;
    private Instant nextAttemptAt;
    private String lastError;
    private Instant processedAt;

    private StripeWebhookEvent(
            String eventId,
            String type,
            String apiVersion,
            boolean livemode,
            Instant stripeCreatedAt,
            String payload,
            WebhookEventStatus status,
            int attempts,
            Instant nextAttemptAt,
            String lastError,
            Instant receivedAt,
            Instant processedAt) {
        this.eventId = eventId;
        this.type = type;
        this.apiVersion = apiVersion;
        this.livemode = livemode;
        this.stripeCreatedAt = stripeCreatedAt;
        this.payload = payload;
        this.status = status;
        this.attempts = attempts;
        this.nextAttemptAt = nextAttemptAt;
        this.lastError = lastError;
        this.receivedAt = receivedAt;
        this.processedAt = processedAt;
    }

    /**
     * A freshly received event, {@link WebhookEventStatus#RECEIVED} and due immediately.
     *
     * @param payload the JSON body as received
     * @throws InvalidPaymentException {@code livemode} is true: only test-mode events are ever accepted (§8.1)
     */
    public static StripeWebhookEvent receive(
            String eventId,
            String type,
            String apiVersion,
            boolean livemode,
            Instant stripeCreatedAt,
            String payload,
            Instant now) {
        if (eventId == null || eventId.isBlank()) {
            throw new InvalidPaymentException("a webhook event needs an id");
        }
        if (type == null || type.isBlank()) {
            throw new InvalidPaymentException("a webhook event needs a type");
        }
        if (livemode) {
            throw new InvalidPaymentException("live-mode event " + eventId + " must never be stored");
        }
        Objects.requireNonNull(stripeCreatedAt, "stripeCreatedAt");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(now, "now");
        return new StripeWebhookEvent(
                eventId,
                type,
                apiVersion,
                false,
                stripeCreatedAt,
                payload,
                WebhookEventStatus.RECEIVED,
                0,
                now,
                null,
                now,
                null);
    }

    /** Rebuilds an event from storage. Nothing is validated. */
    public static StripeWebhookEvent restore(
            String eventId,
            String type,
            String apiVersion,
            boolean livemode,
            Instant stripeCreatedAt,
            String payload,
            WebhookEventStatus status,
            int attempts,
            Instant nextAttemptAt,
            String lastError,
            Instant receivedAt,
            Instant processedAt) {
        return new StripeWebhookEvent(
                eventId,
                type,
                apiVersion,
                livemode,
                stripeCreatedAt,
                payload,
                status,
                attempts,
                nextAttemptAt,
                lastError,
                receivedAt,
                processedAt);
    }

    public void markProcessed(Instant now) {
        finish(WebhookEventStatus.PROCESSED, now);
    }

    public void markIgnored(Instant now) {
        finish(WebhookEventStatus.IGNORED, now);
    }

    public void markStaleIgnored(Instant now) {
        finish(WebhookEventStatus.STALE_IGNORED, now);
    }

    private void finish(WebhookEventStatus target, Instant now) {
        Objects.requireNonNull(now, "now");
        requireOpen();
        status = target;
        processedAt = now;
        nextAttemptAt = null;
    }

    /** Takes the event for a processor: not due again before {@code until}. */
    public void leaseUntil(Instant until) {
        Objects.requireNonNull(until, "until");
        requireOpen();
        nextAttemptAt = until;
    }

    /**
     * Processing failed. The event becomes {@code FAILED} and is due again with backoff, or {@code DEAD} after
     * {@code maxAttempts} failures.
     */
    public RetryDecision scheduleRetry(Instant now, RetryPolicy policy, RandomGenerator random, String error) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(random, "random");
        requireOpen();
        attempts++;
        lastError = error == null || error.length() <= Payment.MAX_ERROR_MESSAGE_LENGTH
                ? error
                : error.substring(0, Payment.MAX_ERROR_MESSAGE_LENGTH);
        if (policy.isExhaustedAfter(attempts)) {
            status = WebhookEventStatus.DEAD;
            nextAttemptAt = null;
            processedAt = now;
            return RetryDecision.EXHAUSTED;
        }
        status = WebhookEventStatus.FAILED;
        nextAttemptAt = policy.nextAttemptAfter(attempts, now, random);
        return RetryDecision.RETRY_SCHEDULED;
    }

    private void requireOpen() {
        if (status != WebhookEventStatus.RECEIVED && status != WebhookEventStatus.FAILED) {
            throw new IllegalStateException("Webhook event " + eventId + " is already " + status);
        }
    }

    public String eventId() {
        return eventId;
    }

    public String type() {
        return type;
    }

    public String apiVersion() {
        return apiVersion;
    }

    public boolean livemode() {
        return livemode;
    }

    public Instant stripeCreatedAt() {
        return stripeCreatedAt;
    }

    public String payload() {
        return payload;
    }

    public WebhookEventStatus status() {
        return status;
    }

    public int attempts() {
        return attempts;
    }

    public Instant nextAttemptAt() {
        return nextAttemptAt;
    }

    public String lastError() {
        return lastError;
    }

    public Instant receivedAt() {
        return receivedAt;
    }

    public Instant processedAt() {
        return processedAt;
    }
}
