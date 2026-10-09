package com.altronixsoft.opp.order.config;

import com.altronixsoft.opp.order.adapter.in.job.PaymentTimeoutJob;
import com.altronixsoft.opp.order.application.ExpireUnpaidOrdersService;
import com.altronixsoft.opp.order.application.IdGenerator;
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

/** Builds the {@link PaymentTimeoutJob} from {@link OrderProperties} and schedules it with a fixed delay. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(OrderProperties.class)
class PaymentTimeoutJobConfiguration implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(PaymentTimeoutJobConfiguration.class);

    private final OrderProperties properties;
    private final PaymentTimeoutJob job;

    PaymentTimeoutJobConfiguration(OrderProperties properties, ExpireUnpaidOrdersService service, IdGenerator ids) {
        this.properties = properties;
        this.job = new PaymentTimeoutJob(
                service,
                ids,
                properties.paymentTimeout(),
                properties.paymentTimeoutJob().batchSize());
    }

    /** Exposed so that operators' tooling and tests can trigger a run; the schedule below uses the same instance. */
    @Bean
    PaymentTimeoutJob paymentTimeoutJob() {
        return job;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        OrderProperties.PaymentTimeoutJob schedule = properties.paymentTimeoutJob();
        if (!schedule.enabled()) {
            log.info("Payment timeout job is disabled (order.payment-timeout-job.enabled=false)");
            return;
        }
        registrar.addFixedDelayTask(new FixedDelayTask(
                () -> {
                    try {
                        job.run();
                    } catch (RuntimeException e) {
                        // The next run retries; a failed batch rolled back and left its orders pending.
                        log.error("Payment timeout run failed", e);
                    }
                },
                schedule.interval(),
                nonNegative(schedule.initialDelay())));
    }

    private static Duration nonNegative(Duration delay) {
        return delay.isNegative() ? Duration.ZERO : delay;
    }
}
