package com.altronixsoft.opp.platform.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.altronixsoft.opp.contracts.EventSerdeException;
import jakarta.validation.ValidationException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.serializer.DeserializationException;

class ConsumerPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ConsumerProperties.class)
    static class Props {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Props.class);

    @Test
    void defaultsMatchTheDocumentedPolicy() {
        runner.run(context -> {
            ConsumerProperties.Retry retry =
                    context.getBean(ConsumerProperties.class).retry();

            assertThat(retry.blockingRetries()).isEqualTo(2);
            assertThat(retry.blockingInterval()).isEqualTo(Duration.ofMillis(200));
            assertThat(retry.topicDelays())
                    .containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(10), Duration.ofSeconds(60));
            assertThat(retry.topicPartitions()).isEqualTo(3);
            assertThat(retry.topicReplicationFactor()).isEqualTo((short) 1);
            assertThat(retry.autoCreateTopics()).isTrue();
            assertThat(retry.nonRetryable()).isEmpty();
        });
    }

    @Test
    void policyIsConfigurable() {
        runner.withPropertyValues(
                        "platform.consumer.retry.blocking-retries=5",
                        "platform.consumer.retry.blocking-interval=1s",
                        "platform.consumer.retry.topic-delays=5s,30s",
                        "platform.consumer.retry.topic-partitions=6",
                        "platform.consumer.retry.topic-replication-factor=3",
                        "platform.consumer.retry.non-retryable=java.lang.ArithmeticException")
                .run(context -> {
                    ConsumerProperties.Retry retry =
                            context.getBean(ConsumerProperties.class).retry();
                    assertThat(retry.blockingRetries()).isEqualTo(5);
                    assertThat(retry.blockingInterval()).isEqualTo(Duration.ofSeconds(1));
                    assertThat(retry.topicDelays()).containsExactly(Duration.ofSeconds(5), Duration.ofSeconds(30));
                    assertThat(retry.topicPartitions()).isEqualTo(6);
                    assertThat(retry.topicReplicationFactor()).isEqualTo((short) 3);
                    assertThat(retry.nonRetryable()).containsExactly(ArithmeticException.class);
                });
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "platform.consumer.retry.blocking-retries=-1",
                "platform.consumer.retry.blocking-retries=21",
                "platform.consumer.retry.blocking-interval=0ms",
                "platform.consumer.retry.topic-delays=1ms",
                "platform.consumer.retry.topic-partitions=0",
                "platform.consumer.retry.topic-replication-factor=0"
            })
    void invalidPolicyFailsStartup(String property) {
        runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void anEmptyDelayListIsRejectedBecauseThereWouldBeNoRetryTopics() {
        runner.withPropertyValues("platform.consumer.retry.topic-delays=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void theNonRetryableListHasTheBuiltInsAndTheConfiguredTypes() {
        ConsumerProperties.Retry retry = new ConsumerProperties.Retry(
                2,
                Duration.ofMillis(200),
                List.of(Duration.ofSeconds(1)),
                3,
                (short) 1,
                true,
                List.of(ArithmeticException.class));

        assertThat(ConsumerAutoConfiguration.nonRetryableExceptions(retry))
                .contains(
                        DeserializationException.class,
                        NonRetryableEventException.class,
                        EventSerdeException.class,
                        ValidationException.class,
                        ArithmeticException.class);
    }
}
