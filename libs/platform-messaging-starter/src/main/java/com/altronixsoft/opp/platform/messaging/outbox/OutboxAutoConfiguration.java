package com.altronixsoft.opp.platform.messaging.outbox;

import com.altronixsoft.opp.contracts.EventSerde;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.flywaydb.core.api.Location;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires the transactional outbox. Requires a {@code DataSource} with a transaction manager and {@code spring.kafka.*}
 * for the relay. When Flyway is used, the starter's migration ({@code classpath:db/migration/platform}) is registered
 * automatically; without Flyway the service must create {@code outbox_event} itself. Disable everything with {@code platform.outbox.enabled=false}, only the relay with
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

    /**
     * Adds the starter's migration location to Flyway, so a service that customizes {@code spring.flyway.locations}
     * still gets {@code outbox_event} without listing the platform location itself.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({FlywayConfigurationCustomizer.class, Location.class})
    static class FlywayConfiguration {

        @Bean
        FlywayConfigurationCustomizer outboxFlywayLocation() {
            return configuration -> {
                Location platform = Location.fromPath("classpath:", "db/migration/platform");
                List<Location> locations = new ArrayList<>(Arrays.asList(configuration.getLocations()));
                if (!locations.contains(platform)) {
                    locations.add(platform);
                    configuration.locations(locations.toArray(Location[]::new));
                }
            };
        }
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
