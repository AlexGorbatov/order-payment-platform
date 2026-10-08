package com.altronixsoft.opp.platform.messaging.outbox;

import com.altronixsoft.opp.contracts.EventSerde;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires the transactional outbox. Requires a {@code DataSource} with a transaction manager and the
 * {@code outbox_event} table (Flyway location {@code classpath:db/migration/platform}), plus {@code spring.kafka.*}
 * for the relay. Disable everything with {@code platform.outbox.enabled=false}, only the relay with
 * {@code platform.outbox.relay.enabled=false}.
 *
 * <p>The scheduled tasks run on Spring's shared task scheduler, whose default pool has a single thread. Services that
 * run other scheduled work should raise {@code spring.task.scheduling.pool.size}, because a relay cycle can block for
 * up to {@code batch-size × ack-timeout} while Kafka is unavailable.
 */
@AutoConfiguration(
        afterName = {
            "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration",
            "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
            "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration",
            "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration",
            "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration"
        })
@ConditionalOnProperty(prefix = "platform.outbox", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    OutboxRepository outboxRepository(JdbcClient jdbcClient) {
        return new OutboxRepository(jdbcClient);
    }

    @Bean
    @ConditionalOnMissingBean
    EventSerde eventSerde() {
        return new EventSerde();
    }

    @Bean
    @ConditionalOnMissingBean
    OutboxMetrics outboxMetrics(
            ObjectProvider<MeterRegistry> registry, OutboxRepository repository, OutboxProperties properties) {
        return new OutboxMetrics(
                registry.getIfAvailable(SimpleMeterRegistry::new),
                repository,
                properties.metrics().cacheTtl());
    }

    @Bean
    @ConditionalOnMissingBean
    OutboxPublisher outboxPublisher(
            OutboxRepository repository, EventSerde serde, ObjectProvider<TraceparentProvider> traceparent) {
        return new OutboxPublisher(repository, serde, traceparent.getIfAvailable(() -> TraceparentProvider.NONE));
    }

    @Bean
    @ConditionalOnMissingBean(name = "outboxTransactionTemplate")
    TransactionTemplate outboxTransactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    /** Captures the {@code traceparent} of the current span when Micrometer Tracing is on the classpath. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Tracer.class)
    static class TracingConfiguration {

        @Bean
        @ConditionalOnMissingBean
        TraceparentProvider outboxTraceparentProvider(ObjectProvider<Tracer> tracer) {
            return TraceparentHeader.from(tracer);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "platform.outbox.relay", name = "enabled", matchIfMissing = true)
    @EnableScheduling
    static class RelayConfiguration {

        @Bean
        @ConditionalOnMissingBean(OutboxSender.class)
        KafkaOutboxSender outboxSender(KafkaProperties kafkaProperties, OutboxProperties properties) {
            return new KafkaOutboxSender(
                    kafkaProperties.buildProducerProperties(),
                    properties.relay().ackTimeout());
        }

        @Bean
        @ConditionalOnMissingBean
        OutboxRelay outboxRelay(
                OutboxRepository repository,
                OutboxSender sender,
                TransactionTemplate outboxTransactionTemplate,
                OutboxMetrics metrics,
                OutboxProperties properties) {
            return new OutboxRelay(
                    repository,
                    sender,
                    outboxTransactionTemplate,
                    metrics,
                    properties.relay().batchSize());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "platform.outbox.cleanup", name = "enabled", matchIfMissing = true)
    @EnableScheduling
    static class CleanupConfiguration {

        @Bean
        @ConditionalOnMissingBean
        OutboxCleanup outboxCleanup(
                OutboxRepository repository,
                TransactionTemplate outboxTransactionTemplate,
                OutboxMetrics metrics,
                OutboxProperties properties) {
            return new OutboxCleanup(
                    repository,
                    outboxTransactionTemplate,
                    metrics,
                    properties.cleanup().retention(),
                    properties.cleanup().batchSize());
        }
    }
}
