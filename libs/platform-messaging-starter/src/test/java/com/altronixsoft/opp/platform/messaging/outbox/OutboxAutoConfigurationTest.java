package com.altronixsoft.opp.platform.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.test.simple.SimpleTracer;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/** Wiring, defaults and property validation, without a database or broker. */
class OutboxAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration.class))
            .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(KafkaProperties.class, KafkaProperties::new)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            // Nothing may actually run in these tests: push the first scheduled cycle far away.
            .withPropertyValues("platform.outbox.relay.initial-delay=1h", "platform.outbox.cleanup.fixed-delay=1h");

    @Test
    void contributesAllBeansWithDocumentedDefaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context)
                    .hasSingleBean(OutboxRepository.class)
                    .hasSingleBean(OutboxPublisher.class)
                    .hasSingleBean(OutboxRelay.class)
                    .hasSingleBean(OutboxCleanup.class)
                    .hasSingleBean(OutboxMetrics.class)
                    .hasSingleBean(KafkaOutboxSender.class);
            OutboxProperties properties = context.getBean(OutboxProperties.class);
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.relay().enabled()).isTrue();
            assertThat(properties.relay().fixedDelay()).isEqualTo(Duration.ofMillis(500));
            assertThat(properties.relay().batchSize()).isEqualTo(100);
            assertThat(properties.relay().ackTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(properties.cleanup().retention()).isEqualTo(Duration.ofDays(7));
            assertThat(properties.cleanup().batchSize()).isEqualTo(1000);
        });
    }

    @Test
    void relayCanBeSwitchedOffWhilePublishingStaysAvailable() {
        runner.withPropertyValues("platform.outbox.relay.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OutboxPublisher.class).hasSingleBean(OutboxCleanup.class);
            assertThat(context).doesNotHaveBean(OutboxRelay.class).doesNotHaveBean(OutboxSender.class);
        });
    }

    @Test
    void cleanupCanBeSwitchedOff() {
        runner.withPropertyValues("platform.outbox.cleanup.enabled=false").run(context -> {
            assertThat(context).hasSingleBean(OutboxRelay.class);
            assertThat(context).doesNotHaveBean(OutboxCleanup.class);
        });
    }

    @Test
    void masterSwitchRemovesEverything() {
        runner.withPropertyValues("platform.outbox.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(OutboxPublisher.class)
                        .doesNotHaveBean(OutboxRelay.class)
                        .doesNotHaveBean(OutboxCleanup.class)
                        .doesNotHaveBean(OutboxRepository.class));
    }

    @Test
    void aServiceSuppliedSenderReplacesTheKafkaOne() {
        OutboxSender custom = message -> {};
        runner.withBean(OutboxSender.class, () -> custom).run(context -> {
            assertThat(context).doesNotHaveBean(KafkaOutboxSender.class);
            assertThat(context.getBean(OutboxSender.class)).isSameAs(custom);
            assertThat(context).hasSingleBean(OutboxRelay.class);
        });
    }

    @Test
    void settingsAreBound() {
        runner.withPropertyValues(
                        "platform.outbox.relay.fixed-delay=2s",
                        "platform.outbox.relay.batch-size=250",
                        "platform.outbox.relay.ack-timeout=30s",
                        "platform.outbox.cleanup.retention=14d",
                        "platform.outbox.cleanup.batch-size=500")
                .run(context -> {
                    OutboxProperties properties = context.getBean(OutboxProperties.class);
                    assertThat(properties.relay().fixedDelay()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(properties.relay().batchSize()).isEqualTo(250);
                    assertThat(properties.relay().ackTimeout()).isEqualTo(Duration.ofSeconds(30));
                    assertThat(properties.cleanup().retention()).isEqualTo(Duration.ofDays(14));
                    assertThat(properties.cleanup().batchSize()).isEqualTo(500);
                });
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "platform.outbox.relay.batch-size=0",
                "platform.outbox.relay.batch-size=1001",
                "platform.outbox.relay.fixed-delay=0s",
                "platform.outbox.relay.ack-timeout=50ms",
                "platform.outbox.cleanup.retention=10s",
                "platform.outbox.cleanup.batch-size=0",
                "platform.outbox.metrics.cache-ttl=0s"
            })
    void invalidSettingsFailStartup(String property) {
        runner.withPropertyValues(property).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("platform.outbox");
        });
    }

    @Test
    void traceparentIsCapturedOnlyWhenATracerIsPresent() {
        runner.run(context ->
                assertThat(context.getBean(TraceparentProvider.class).current()).isEmpty());
        runner.withBean(SimpleTracer.class, SimpleTracer::new).run(context -> {
            assertThat(context).hasSingleBean(TraceparentProvider.class);
            // A tracer without a current span still yields no header.
            assertThat(context.getBean(TraceparentProvider.class).current()).isEmpty();
        });
    }
}
