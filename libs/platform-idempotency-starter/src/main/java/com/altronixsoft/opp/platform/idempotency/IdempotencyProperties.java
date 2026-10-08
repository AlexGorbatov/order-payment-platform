package com.altronixsoft.opp.platform.idempotency;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of HTTP idempotency ({@code platform.idempotency.*}).
 *
 * <p>Defaults are repeated in the {@code @Scheduled} placeholders of {@link IdempotencyCleanup}; keep them in sync.
 *
 * @param enabled master switch; when {@code false} the starter contributes no beans
 * @param maxBodySize largest request body of a request that carries an {@code Idempotency-Key}; larger ones get 413
 * @param inProgressTimeout after this long an {@code IN_PROGRESS} record is an abandoned claim (the process died after
 *     claiming) and the next request with the same key may take it over; must exceed the slowest idempotent request
 * @param replayHeaders response headers that are stored and replayed (case-insensitive)
 * @param filterOrder order of the buffering filter; it must run after Spring Security's chain (order -100)
 * @param cleanup removal of expired records
 */
@Validated
@ConfigurationProperties("platform.idempotency")
public record IdempotencyProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1MB") @NotNull DataSize maxBodySize,

        @DefaultValue("2m") @NotNull @DurationMin(seconds = 5)
        Duration inProgressTimeout,

        @DefaultValue({"Content-Type", "Location", "Content-Language", "ETag"}) @NotEmpty
        List<String> replayHeaders,

        @DefaultValue("0") int filterOrder,
        @Valid @DefaultValue Cleanup cleanup) {

    /**
     * @param enabled run the cleanup in this instance
     * @param fixedDelay pause between cleanup runs
     * @param batchSize rows deleted per statement
     */
    public record Cleanup(
            @DefaultValue("true") boolean enabled,

            @DefaultValue("10m") @NotNull @DurationMin(seconds = 1)
            Duration fixedDelay,

            @DefaultValue("1000") @Min(1) @Max(100_000) int batchSize) {}
}
