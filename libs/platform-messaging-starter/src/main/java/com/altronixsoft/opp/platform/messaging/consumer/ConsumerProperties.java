package com.altronixsoft.opp.platform.messaging.consumer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Consumer reliability policy ({@code platform.consumer.*}, architecture §7.4): blocking retries on the source topic,
 * then non-blocking retry topics, then the dead-letter topic.
 *
 * @param enabled master switch; when {@code false} the starter configures neither retry topics nor consumer defaults
 * @param defaults consumer defaults applied through {@code spring.kafka.*} when the service does not set them itself
 * @param retry retry policy
 */
@Validated
@ConfigurationProperties("platform.consumer")
public record ConsumerProperties(
        @DefaultValue("true") boolean enabled,
        @Valid @DefaultValue Defaults defaults,
        @Valid @DefaultValue Retry retry) {

    /**
     * @param enabled apply the defaults ({@code ErrorHandlingDeserializer}, manual offset commit, ack mode RECORD,
     *     {@code read_committed}, {@code earliest}); read from the environment before binding, see
     *     {@link ConsumerDefaultsEnvironmentPostProcessor}
     */
    public record Defaults(@DefaultValue("true") boolean enabled) {}

    /**
     * @param blockingRetries how many times a failed record is retried on the source topic before it moves on
     * @param blockingInterval pause between blocking retries
     * @param topicDelays one delay per retry topic; the record is delivered to the next retry topic after the previous
     *     delay and goes to the DLT after the last one
     * @param topicPartitions partitions of auto-created retry and dead-letter topics
     * @param topicReplicationFactor replication factor of auto-created retry and dead-letter topics
     * @param autoCreateTopics let Spring Kafka create missing retry and dead-letter topics at startup
     * @param nonRetryable additional exception types that go straight to the DLT (besides deserialization errors,
     *     {@link NonRetryableEventException}, event-contract errors and bean-validation errors)
     */
    public record Retry(
            @DefaultValue("2") @Min(0) @Max(20) int blockingRetries,

            @DefaultValue("200ms") @NotNull @DurationMin(millis = 1)
            Duration blockingInterval,

            @DefaultValue({"1s", "10s", "60s"}) @NotEmpty @Size(max = 10)
            List<@NotNull @DurationMin(millis = 10) Duration> topicDelays,

            @DefaultValue("3") @Min(1) int topicPartitions,
            @DefaultValue("1") @Min(1) short topicReplicationFactor,
            @DefaultValue("true") boolean autoCreateTopics,
            @DefaultValue List<Class<? extends Throwable>> nonRetryable) {}
}
