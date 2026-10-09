package com.altronixsoft.opp.payment.config;

import com.altronixsoft.opp.payment.adapter.in.job.ReconciliationJob;
import com.altronixsoft.opp.payment.adapter.out.stripe.StripeCallRateLimiter;
import com.altronixsoft.opp.payment.application.PaymentEventPublisher;
import com.altronixsoft.opp.payment.application.PaymentGateway;
import com.altronixsoft.opp.payment.application.PaymentRepository;
import com.altronixsoft.opp.payment.application.ReconcilePaymentsService;
import com.altronixsoft.opp.payment.application.ReconciliationRecorder;
import com.altronixsoft.opp.payment.application.ReconciliationSettings;
import java.time.Clock;
import java.time.Duration;
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

/** Builds reconciliation (architecture §8.4) from {@link PaymentProperties} and schedules it with a fixed delay. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(PaymentProperties.class)
class ReconciliationConfiguration implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationConfiguration.class);

    private final PaymentProperties.Reconciliation properties;
    private final ReconciliationJob job;

    ReconciliationConfiguration(PaymentProperties properties, ReconciliationJob job) {
        this.properties = properties.reconciliation();
        this.job = job;
    }

    @Bean
    static ReconcilePaymentsService reconcilePaymentsService(
            PaymentProperties properties,
            PaymentRepository payments,
            PaymentGateway gateway,
            PaymentEventPublisher events,
            ReconciliationRecorder recorder,
            TransactionOperations transactions,
            Clock clock) {
        PaymentProperties.Reconciliation p = properties.reconciliation();
        return new ReconcilePaymentsService(
                payments,
                gateway,
                events,
                new StripeCallRateLimiter("reconciliation", p.rateLimitPerSecond(), p.rateLimitMaxWait()),
                recorder,
                transactions,
                clock,
                new ReconciliationSettings(p.staleAfter(), p.batchSize()));
    }

    @Bean
    static ReconciliationJob reconciliationJob(ReconcilePaymentsService service) {
        return new ReconciliationJob(service);
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (!properties.enabled()) {
            log.info("Reconciliation is disabled (payment.reconciliation.enabled=false)");
            return;
        }
        registrar.addFixedDelayTask(new FixedDelayTask(
                () -> {
                    try {
                        job.run();
                    } catch (RuntimeException e) {
                        // A claimed payment keeps its mark until the stale period passes, then it is checked again.
                        log.error("Reconciliation run failed", e);
                    }
                },
                properties.interval(),
                properties.initialDelay().isNegative() ? Duration.ZERO : properties.initialDelay()));
    }
}
