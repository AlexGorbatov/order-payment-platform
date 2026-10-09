package com.altronixsoft.opp.payment.config;

import com.altronixsoft.opp.payment.adapter.in.job.WebhookProcessorJob;
import com.altronixsoft.opp.payment.application.PaymentEventPublisher;
import com.altronixsoft.opp.payment.application.PaymentRepository;
import com.altronixsoft.opp.payment.application.ProcessWebhookEventsService;
import com.altronixsoft.opp.payment.application.RefundRepository;
import com.altronixsoft.opp.payment.application.StripeNotificationHandler;
import com.altronixsoft.opp.payment.application.WebhookEventRepository;
import com.altronixsoft.opp.payment.application.WebhookPayloadParser;
import com.altronixsoft.opp.payment.application.WebhookSettings;
import com.altronixsoft.opp.payment.domain.RetryPolicy;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Builds the {@link ProcessWebhookEventsService} from {@link PaymentProperties} and schedules the
 * {@link WebhookProcessorJob} with a fixed delay.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(PaymentProperties.class)
class WebhookProcessingConfiguration implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebhookProcessingConfiguration.class);

    private final PaymentProperties.WebhookProcessor properties;
    private final WebhookProcessorJob job;

    WebhookProcessingConfiguration(PaymentProperties properties, WebhookProcessorJob job) {
        this.properties = properties.webhookProcessor();
        this.job = job;
    }

    @Bean
    static WebhookSettings webhookSettings(PaymentProperties properties) {
        PaymentProperties.WebhookProcessor p = properties.webhookProcessor();
        return new WebhookSettings(
                p.batchSize(),
                p.lease(),
                new RetryPolicy(p.retryBaseDelay(), p.retryMaxDelay(), p.retryMaxAttempts(), p.retryJitter()));
    }

    @Bean
    static WebhookProcessorJob webhookProcessorJob(
            WebhookEventRepository webhookEvents,
            WebhookPayloadParser parser,
            PaymentRepository payments,
            RefundRepository refunds,
            PaymentEventPublisher events,
            TransactionOperations transactions,
            Clock clock,
            RandomGenerator random,
            WebhookSettings settings,
            MeterRegistry meters) {
        StripeNotificationHandler handler = new StripeNotificationHandler(payments, refunds, events, clock);
        return new WebhookProcessorJob(
                new ProcessWebhookEventsService(webhookEvents, parser, handler, transactions, clock, random, settings),
                meters);
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (!properties.enabled()) {
            log.info("Webhook processor is disabled (payment.webhook-processor.enabled=false)");
            return;
        }
        registrar.addFixedDelayTask(new FixedDelayTask(
                () -> {
                    try {
                        job.run();
                    } catch (RuntimeException e) {
                        // Claimed events keep their lease and come back when it expires.
                        log.error("Webhook processing run failed", e);
                    }
                },
                properties.interval(),
                properties.initialDelay().isNegative() ? Duration.ZERO : properties.initialDelay()));
    }
}
