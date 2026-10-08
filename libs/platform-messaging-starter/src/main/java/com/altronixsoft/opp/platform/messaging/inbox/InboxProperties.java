package com.altronixsoft.opp.platform.messaging.inbox;

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
 * Settings of the consumer inbox ({@code platform.inbox.*}).
 *
 * <p>Defaults are repeated in the {@code @Scheduled} placeholders of {@link InboxCleanup}; keep them in sync.
 *
 * @param enabled master switch; when {@code false} the starter contributes no inbox beans
 * @param cleanup retention cleanup
 */
@Validated
@ConfigurationProperties("platform.inbox")
public record InboxProperties(
        @DefaultValue("true") boolean enabled,
        @Valid @DefaultValue Cleanup cleanup) {

    /**
     * @param enabled run the cleanup in this instance
     * @param retention how long inbox rows are kept; must exceed the topic retention (7 days) so that every redelivery
     *     is still recognised (architecture §7.2: 14 days)
     * @param fixedDelay pause between cleanup runs
     * @param batchSize rows deleted per statement
     */
    public record Cleanup(
            @DefaultValue("true") boolean enabled,

            @DefaultValue("14d") @NotNull @DurationMin(days = 1)
            Duration retention,

            @DefaultValue("1h") @NotNull @DurationMin(seconds = 1)
            Duration fixedDelay,

            @DefaultValue("1000") @Min(1) @Max(100_000) int batchSize) {}
}
