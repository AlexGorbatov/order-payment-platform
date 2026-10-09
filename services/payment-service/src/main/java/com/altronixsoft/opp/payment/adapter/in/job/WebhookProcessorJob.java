package com.altronixsoft.opp.payment.adapter.in.job;

import com.altronixsoft.opp.payment.application.ProcessWebhookEventsService;
import com.altronixsoft.opp.payment.application.WebhookBatchResult;
import com.altronixsoft.opp.payment.application.WebhookOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The scheduled entry of the {@code WebhookProcessor}: runs one batch of {@link ProcessWebhookEventsService} and turns the
 * result into metrics (architecture §13). The schedule lives in {@code WebhookProcessingConfiguration}; tests and
 * operators' tooling call {@link #run()} directly.
 *
 * <ul>
 *   <li>{@code webhook.processed{outcome}} for every claimed event;
 *   <li>{@code webhook.stale.ignored} for reports older than what was applied (F10);
 *   <li>{@code webhook.dead} for events that ran out of attempts (F14) — alert on any increase;
 *   <li>{@code webhook.processing.lag}: arrival to final status.
 * </ul>
 */
public class WebhookProcessorJob {

    static final String METRIC = "webhook.processed";

    private static final Logger log = LoggerFactory.getLogger(WebhookProcessorJob.class);

    private final ProcessWebhookEventsService service;
    private final MeterRegistry meters;
    private final Timer lag;

    public WebhookProcessorJob(ProcessWebhookEventsService service, MeterRegistry meters) {
        this.service = service;
        this.meters = meters;
        this.lag = Timer.builder("webhook.processing.lag")
                .description("Time from receiving a webhook to its final status")
                .register(meters);
    }

    /** @return what the run did; empty if nothing was due */
    public WebhookBatchResult run() {
        WebhookBatchResult result = service.runBatch();
        for (WebhookOutcome outcome : WebhookOutcome.values()) {
            int count = result.count(outcome);
            if (count > 0) {
                meters.counter(METRIC, "outcome", outcome.name().toLowerCase(Locale.ROOT))
                        .increment(count);
            }
        }
        if (result.count(WebhookOutcome.STALE) > 0) {
            meters.counter("webhook.stale.ignored").increment(result.count(WebhookOutcome.STALE));
        }
        if (!result.dead().isEmpty()) {
            meters.counter("webhook.dead").increment(result.dead().size());
        }
        for (Duration duration : result.lags()) {
            lag.record(duration);
        }
        if (result.total() > 0) {
            log.info("Webhook processing run: {}", result.counts());
        }
        return result;
    }
}
