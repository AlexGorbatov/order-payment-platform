package com.altronixsoft.opp.payment.config;

import com.altronixsoft.opp.payment.application.WorkerSettings;
import com.altronixsoft.opp.payment.domain.RetryPolicy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of payment-service's own behaviour (prefix {@code payment}); the Stripe adapter has its own ({@code stripe.*}).
 *
 * @param initiation the PaymentInitiationWorker
 * @param webhookProcessor the WebhookProcessor
 * @param cancellation the PaymentCancellationWorker
 * @param refund the RefundWorker
 * @param reconciliation the ReconciliationJob
 */
@ConfigurationProperties("payment")
record PaymentProperties(
        @DefaultValue Initiation initiation,
        @DefaultValue WebhookProcessor webhookProcessor,
        @DefaultValue Worker cancellation,
        @DefaultValue Worker refund,
        @DefaultValue Reconciliation reconciliation) {

    /**
     * Reconciliation with Stripe (architecture §8.4, ADR-0010).
     *
     * @param enabled whether the scheduled job runs; the manual trigger works either way
     * @param interval delay between the end of one run and the start of the next
     * @param initialDelay delay before the first run
     * @param staleAfter a payment unchanged (and unchecked) for this long is checked
     * @param batchSize payments checked per run at most
     * @param rateLimitPerSecond Stripe calls per second the job may make
     * @param rateLimitMaxWait how long the job waits for a permit before it leaves the rest for the next run
     */
    record Reconciliation(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("5m") Duration interval,
            @DefaultValue("1m") Duration initialDelay,
            @DefaultValue("10m") Duration staleAfter,
            @DefaultValue("200") int batchSize,
            @DefaultValue("5") int rateLimitPerSecond,
            @DefaultValue("5s") Duration rateLimitMaxWait) {}

    /**
     * Creates the PaymentIntent of payments waiting in {@code CREATED} (architecture §6.1, ADR-0008).
     *
     * @param enabled whether the scheduled worker runs; the bean that does the work exists either way
     * @param interval delay between the end of one run and the start of the next
     * @param initialDelay delay before the first run
     * @param batchSize payments claimed per run
     * @param lease how long a claimed payment is not due again; must exceed the longest Stripe call for the whole batch
     * @param idempotencyWindow how long a payment may wait in {@code CREATED}: Stripe keeps idempotency keys for at least
     *     24 hours, so after this window the payment is failed instead of retried with a possibly forgotten key (F08)
     * @param retryBaseDelay delay after the first transient failure
     * @param retryMaxDelay upper bound of the backoff
     * @param retryMaxAttempts transient failures after which the initiation is given up
     * @param retryJitter fraction of the backoff that may be shaved off at random
     * @param deferral wait after a failure that is not the payment's fault (open circuit breaker, configuration problem)
     */
    record Initiation(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("2s") Duration interval,
            @DefaultValue("5s") Duration initialDelay,
            @DefaultValue("10") int batchSize,
            @DefaultValue("5m") Duration lease,
            @DefaultValue("23h") Duration idempotencyWindow,
            @DefaultValue("2s") Duration retryBaseDelay,
            @DefaultValue("5m") Duration retryMaxDelay,
            @DefaultValue("8") int retryMaxAttempts,
            @DefaultValue("0.2") double retryJitter,
            @DefaultValue("30s") Duration deferral) {}

    /**
     * A DB-backed worker that calls Stripe (architecture §7.6, ADR-0008): the PaymentCancellationWorker
     * ({@code payment.cancellation}) and the RefundWorker ({@code payment.refund}).
     *
     * @param enabled whether the scheduled worker runs; the bean that does the work exists either way
     * @param interval delay between the end of one run and the start of the next
     * @param initialDelay delay before the first run
     * @param batchSize items claimed per run
     * @param lease how long a claimed item is not due again; must exceed the Stripe calls of a whole batch
     * @param retryBaseDelay delay after the first transient failure
     * @param retryMaxDelay upper bound of the backoff
     * @param retryMaxAttempts transient failures after which the worker gives up on the item
     * @param retryJitter fraction of the backoff that may be shaved off at random
     * @param deferral wait after a failure that is not the item's fault (open circuit breaker, configuration problem)
     */
    record Worker(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("2s") Duration interval,
            @DefaultValue("5s") Duration initialDelay,
            @DefaultValue("10") int batchSize,
            @DefaultValue("5m") Duration lease,
            @DefaultValue("2s") Duration retryBaseDelay,
            @DefaultValue("5m") Duration retryMaxDelay,
            @DefaultValue("8") int retryMaxAttempts,
            @DefaultValue("0.2") double retryJitter,
            @DefaultValue("30s") Duration deferral) {

        WorkerSettings settings() {
            return new WorkerSettings(
                    batchSize,
                    lease,
                    new RetryPolicy(retryBaseDelay, retryMaxDelay, retryMaxAttempts, retryJitter),
                    deferral);
        }
    }

    /**
     * Processes the stored Stripe webhook events (architecture §6.6, ADR-0009).
     *
     * @param enabled whether the scheduled processor runs; the bean that does the work exists either way
     * @param interval delay between the end of one run and the start of the next
     * @param initialDelay delay before the first run
     * @param batchSize events claimed per run
     * @param lease how long a claimed event is not due again
     * @param retryBaseDelay delay after the first failed attempt
     * @param retryMaxDelay upper bound of the backoff
     * @param retryMaxAttempts failed attempts after which the event is DEAD (F14)
     * @param retryJitter fraction of the backoff that may be shaved off at random
     */
    record WebhookProcessor(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("1s") Duration interval,
            @DefaultValue("5s") Duration initialDelay,
            @DefaultValue("50") int batchSize,
            @DefaultValue("2m") Duration lease,
            @DefaultValue("2s") Duration retryBaseDelay,
            @DefaultValue("5m") Duration retryMaxDelay,
            @DefaultValue("8") int retryMaxAttempts,
            @DefaultValue("0.2") double retryJitter) {}
}
