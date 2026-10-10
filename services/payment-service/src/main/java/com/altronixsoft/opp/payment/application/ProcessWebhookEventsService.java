package com.altronixsoft.opp.payment.application;

import com.altronixsoft.opp.payment.domain.RetryDecision;
import com.altronixsoft.opp.payment.domain.StripeWebhookEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Use case: the {@code WebhookProcessor} (architecture §6.6, §7.6, ADR-0009). Works through the stored webhook events.
 *
 * <ol>
 *   <li><b>Claim</b> (transaction): due {@code RECEIVED} and {@code FAILED} events are locked with
 *       {@code FOR UPDATE SKIP LOCKED} and leased, so concurrent processors never take the same event.
 *   <li><b>Process</b> (one transaction per event): the payload is parsed, the {@link StripeNotificationHandler} changes
 *       the payment or refund and writes the outbox, and the event becomes {@code PROCESSED} or {@code IGNORED} — all
 *       committed together. A stale report is not an error: the event is {@code PROCESSED}.
 *   <li><b>On failure</b> (new transaction): everything above rolled back; the event becomes {@code FAILED} with
 *       exponential backoff, or {@code DEAD} after {@code maxAttempts} (F14). An operator replays a dead event.
 * </ol>
 *
 * No Stripe call happens here: a webhook carries the state it reports. One event's failure never stops the others.
 */
public class ProcessWebhookEventsService {

    private static final Logger log = LoggerFactory.getLogger(ProcessWebhookEventsService.class);

    private final WebhookEventRepository webhookEvents;
    private final WebhookPayloadParser parser;
    private final StripeNotificationHandler handler;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final RandomGenerator random;
    private final WebhookSettings settings;

    public ProcessWebhookEventsService(
            WebhookEventRepository webhookEvents,
            WebhookPayloadParser parser,
            StripeNotificationHandler handler,
            TransactionOperations transactions,
            Clock clock,
            RandomGenerator random,
            WebhookSettings settings) {
        this.webhookEvents = webhookEvents;
        this.parser = parser;
        this.handler = handler;
        this.transactions = transactions;
        this.clock = clock;
        this.random = random;
        this.settings = settings;
    }

    /** Processes one batch of due events; an empty result means nothing was due. */
    public WebhookBatchResult runBatch() {
        WebhookBatchResult.Builder result = new WebhookBatchResult.Builder();
        for (String eventId : claim()) {
            process(eventId, result);
        }
        return result.build();
    }

    private List<String> claim() {
        List<String> claimed = transactions.execute(status -> {
            Instant now = now();
            List<String> ids = new ArrayList<>();
            for (StripeWebhookEvent event : webhookEvents.claimDueBatch(now, settings.batchSize())) {
                event.leaseUntil(now.plus(settings.lease()));
                webhookEvents.save(event);
                ids.add(event.eventId());
            }
            return ids;
        });
        return claimed == null ? List.of() : claimed;
    }

    private void process(String eventId, WebhookBatchResult.Builder result) {
        MDC.put("stripeEventId", eventId);
        try {
            Finished finished = transactions.execute(status -> apply(eventId));
            if (finished == null || finished.outcome() == WebhookOutcome.SKIPPED) {
                result.add(WebhookOutcome.SKIPPED);
                return;
            }
            result.add(finished.outcome());
            result.lag(finished.lag());
        } catch (RuntimeException failure) {
            recordFailure(eventId, failure, result);
        } finally {
            StripeNotificationHandler.clearLoggingContext();
            MDC.remove("stripeEventId");
        }
    }

    private record Finished(WebhookOutcome outcome, Duration lag) {}

    private Finished apply(String eventId) {
        StripeWebhookEvent event = webhookEvents.findById(eventId).orElse(null);
        if (event == null || !event.isOpen()) {
            return new Finished(WebhookOutcome.SKIPPED, Duration.ZERO);
        }
        StripeNotification notification = parser.parse(event.type(), event.payload());
        NotificationOutcome outcome = handler.handle(notification, event);
        Instant now = now();
        WebhookOutcome result = switch (outcome) {
            case APPLIED -> {
                event.markProcessed(now);
                yield WebhookOutcome.PROCESSED;
            }
            case STALE -> {
                event.markProcessed(now);
                yield WebhookOutcome.STALE;
            }
            case IGNORED -> {
                event.markIgnored(now);
                yield WebhookOutcome.IGNORED;
            }
        };
        webhookEvents.save(event);
        return new Finished(result, Duration.between(event.receivedAt(), now));
    }

    private void recordFailure(String eventId, RuntimeException failure, WebhookBatchResult.Builder result) {
        String error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        try {
            WebhookOutcome outcome = transactions.execute(status -> {
                StripeWebhookEvent event = webhookEvents.findById(eventId).orElse(null);
                if (event == null || !event.isOpen()) {
                    return WebhookOutcome.SKIPPED;
                }
                Instant now = now();
                RetryDecision decision = event.scheduleRetry(now, settings.retryPolicy(), random, error);
                webhookEvents.save(event);
                if (decision == RetryDecision.EXHAUSTED) {
                    log.error(
                            "Webhook {} ({}) is DEAD after {} failed attempts; last error: {}. Replay it with runbook "
                                    + "webhooks.md once the cause is fixed.",
                            eventId,
                            event.type(),
                            event.attempts(),
                            error);
                    return WebhookOutcome.DEAD;
                }
                log.warn(
                        "Webhook {} ({}) failed (attempt {}), next attempt at {}: {}",
                        eventId,
                        event.type(),
                        event.attempts(),
                        event.nextAttemptAt(),
                        error);
                return WebhookOutcome.RETRY_SCHEDULED;
            });
            WebhookOutcome recorded = outcome == null ? WebhookOutcome.ERROR : outcome;
            result.add(recorded);
            if (recorded == WebhookOutcome.DEAD) {
                result.dead(eventId);
            }
        } catch (RuntimeException e) {
            log.error("Could not record the failure of webhook {}; it is retried when its lease expires", eventId, e);
            result.add(WebhookOutcome.ERROR);
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
