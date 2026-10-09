package com.altronixsoft.opp.payment.config;

import com.altronixsoft.opp.payment.adapter.in.job.PaymentInitiationJob;
import com.altronixsoft.opp.payment.application.InitiatePaymentsService;
import com.altronixsoft.opp.payment.application.InitiationSettings;
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
 * Builds the {@link InitiatePaymentsService} from {@link PaymentProperties} and schedules the {@link PaymentInitiationJob}
 * with a fixed delay.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(PaymentProperties.class)
class InitiationJobConfiguration implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(InitiationJobConfiguration.class);

    private final PaymentProperties.Initiation properties;
    private final PaymentInitiationJob job;

    InitiationJobConfiguration(PaymentProperties properties, PaymentInitiationJob job) {
        this.properties = properties.initiation();
        this.job = job;
    }

    @Bean
    static InitiationSettings initiationSettings(PaymentProperties properties) {
        PaymentProperties.Initiation p = properties.initiation();
        return new InitiationSettings(
                p.batchSize(),
                p.lease(),
                p.idempotencyWindow(),
                new RetryPolicy(p.retryBaseDelay(), p.retryMaxDelay(), p.retryMaxAttempts(), p.retryJitter()),
                p.deferral());
    }

    @Bean
    static PaymentInitiationJob paymentInitiationJob(
            com.altronixsoft.opp.payment.application.PaymentRepository payments,
            com.altronixsoft.opp.payment.application.PaymentGateway gateway,
            com.altronixsoft.opp.payment.application.PaymentEventPublisher events,
            TransactionOperations transactions,
            Clock clock,
            RandomGenerator random,
            InitiationSettings settings,
            MeterRegistry meters) {
        return new PaymentInitiationJob(
                new InitiatePaymentsService(payments, gateway, events, transactions, clock, random, settings), meters);
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (!properties.enabled()) {
            log.info("Payment initiation worker is disabled (payment.initiation.enabled=false)");
            return;
        }
        registrar.addFixedDelayTask(new FixedDelayTask(
                () -> {
                    try {
                        job.run();
                    } catch (RuntimeException e) {
                        // The next run retries; claimed payments keep their lease and come back when it expires.
                        log.error("Payment initiation run failed", e);
                    }
                },
                properties.interval(),
                nonNegative(properties.initialDelay())));
    }

    private static Duration nonNegative(Duration delay) {
        return delay.isNegative() ? Duration.ZERO : delay;
    }
}
