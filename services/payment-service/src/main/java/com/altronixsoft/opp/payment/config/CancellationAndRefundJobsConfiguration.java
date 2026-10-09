package com.altronixsoft.opp.payment.config;

import com.altronixsoft.opp.payment.adapter.in.job.PaymentCancellationJob;
import com.altronixsoft.opp.payment.adapter.in.job.RefundJob;
import com.altronixsoft.opp.payment.application.CancelPaymentIntentsService;
import com.altronixsoft.opp.payment.application.CreateRefundsService;
import com.altronixsoft.opp.payment.application.PaymentEventPublisher;
import com.altronixsoft.opp.payment.application.PaymentGateway;
import com.altronixsoft.opp.payment.application.PaymentRepository;
import com.altronixsoft.opp.payment.application.RefundRepository;
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
 * Builds the PaymentCancellationWorker and the RefundWorker (architecture §6.4, §6.5, §7.6) from
 * {@link PaymentProperties} and schedules them with a fixed delay each.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(PaymentProperties.class)
class CancellationAndRefundJobsConfiguration implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CancellationAndRefundJobsConfiguration.class);

    private final PaymentProperties properties;
    private final PaymentCancellationJob cancellationJob;
    private final RefundJob refundJob;

    CancellationAndRefundJobsConfiguration(
            PaymentProperties properties, PaymentCancellationJob cancellationJob, RefundJob refundJob) {
        this.properties = properties;
        this.cancellationJob = cancellationJob;
        this.refundJob = refundJob;
    }

    @Bean
    static PaymentCancellationJob paymentCancellationJob(
            PaymentProperties properties,
            PaymentRepository payments,
            PaymentGateway gateway,
            TransactionOperations transactions,
            Clock clock,
            RandomGenerator random,
            MeterRegistry meters) {
        return new PaymentCancellationJob(
                new CancelPaymentIntentsService(
                        payments,
                        gateway,
                        transactions,
                        clock,
                        random,
                        properties.cancellation().settings()),
                meters);
    }

    @Bean
    static RefundJob refundJob(
            PaymentProperties properties,
            RefundRepository refunds,
            PaymentRepository payments,
            PaymentGateway gateway,
            PaymentEventPublisher events,
            TransactionOperations transactions,
            Clock clock,
            RandomGenerator random,
            MeterRegistry meters) {
        return new RefundJob(
                new CreateRefundsService(
                        refunds,
                        payments,
                        gateway,
                        events,
                        transactions,
                        clock,
                        random,
                        properties.refund().settings()),
                meters);
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        schedule(registrar, "payment.cancellation", properties.cancellation(), cancellationJob::run);
        schedule(registrar, "payment.refund", properties.refund(), refundJob::run);
    }

    private static void schedule(
            ScheduledTaskRegistrar registrar, String name, PaymentProperties.Worker worker, Runnable run) {
        if (!worker.enabled()) {
            log.info("Worker {} is disabled ({}.enabled=false)", name, name);
            return;
        }
        registrar.addFixedDelayTask(new FixedDelayTask(
                () -> {
                    try {
                        run.run();
                    } catch (RuntimeException e) {
                        // Claimed items keep their lease and come back when it expires.
                        log.error("Worker {} run failed", name, e);
                    }
                },
                worker.interval(),
                worker.initialDelay().isNegative() ? Duration.ZERO : worker.initialDelay()));
    }
}
