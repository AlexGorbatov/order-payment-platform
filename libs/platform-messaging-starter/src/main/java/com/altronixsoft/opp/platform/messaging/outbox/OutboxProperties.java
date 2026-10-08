package com.altronixsoft.opp.platform.messaging.outbox;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of the transactional outbox ({@code platform.outbox.*}). Invalid values fail application startup.
 *
 * <p>Defaults are repeated in the {@code @Scheduled} placeholders of {@link OutboxRelay} and {@link OutboxCleanup}; keep
 * them in sync.
 *
 * @param enabled master switch; when {@code false} the starter contributes no beans
 * @param relay polling relay settings
 * @param cleanup retention cleanup settings
 * @param metrics gauge settings
 */
@Validated
@ConfigurationProperties("platform.outbox")
public record OutboxProperties(
        @DefaultValue("true") boolean enabled,
        @Valid @DefaultValue Relay relay,
        @Valid @DefaultValue Cleanup cleanup,
        @Valid @DefaultValue Metrics metrics) {

    /**
     * @param enabled run the relay in this instance; turn off for instances that must only write events
     * @param fixedDelay pause between the end of one poll cycle and the start of the next
     * @param initialDelay delay before the first cycle
     * @param batchSize maximum rows claimed per cycle
     * @param ackTimeout how long to wait for the broker's acknowledgement of one record; also bounds the Kafka producer's
     *     delivery timeout, so a hung broker costs at most this long per cycle
     */
    public record Relay(
            @DefaultValue("true") boolean enabled,

            @DefaultValue("500ms") @NotNull @DurationMin(millis = 10)
            Duration fixedDelay,

            @DefaultValue("1s") @NotNull @DurationMin(nanos = 0)
            Duration initialDelay,

            @DefaultValue("100") @Min(1) @Max(1000) int batchSize,

            @DefaultValue("10s") @NotNull @DurationMin(millis = 200)
            Duration ackTimeout) {}

    /**
     * @param enabled run the cleanup in this instance
     * @param retention how long published rows are kept (architecture §7.1: 7 days)
     * @param fixedDelay pause between cleanup runs
     * @param batchSize rows deleted per statement, to keep each transaction short
     */
    public record Cleanup(
            @DefaultValue("true") boolean enabled,

            @DefaultValue("7d") @NotNull @DurationMin(minutes = 1)
            Duration retention,

            @DefaultValue("1h") @NotNull @DurationMin(seconds = 1)
            Duration fixedDelay,

            @DefaultValue("1000") @Min(1) @Max(100_000) int batchSize) {}

    /** @param cacheTtl the pending/oldest gauges query the database at most this often */
    public record Metrics(
            @DefaultValue("5s") @NotNull @DurationMin(millis = 100)
            Duration cacheTtl) {}
}
