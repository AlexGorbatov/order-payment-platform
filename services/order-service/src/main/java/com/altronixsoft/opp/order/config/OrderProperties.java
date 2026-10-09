package com.altronixsoft.opp.order.config;

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
 * Order lifecycle settings ({@code order.*}).
 *
 * @param paymentTimeout how long an order may wait for its payment before it is cancelled with reason
 *     {@code TIMEOUT} (architecture §6.4); {@code PT3M} in the {@code local} profile
 * @param paymentTimeoutJob the job that enforces it
 */
@Validated
@ConfigurationProperties("order")
record OrderProperties(
        @DefaultValue("PT30M") @NotNull @DurationMin(seconds = 1)
        Duration paymentTimeout,

        @Valid @DefaultValue PaymentTimeoutJob paymentTimeoutJob) {

    /**
     * @param enabled schedule the job at all
     * @param initialDelay pause after startup before the first run
     * @param interval pause between the end of one run and the start of the next
     * @param batchSize orders cancelled per transaction
     */
    record PaymentTimeoutJob(
            @DefaultValue("true") boolean enabled,

            @DefaultValue("10s") @NotNull @DurationMin(millis = 0)
            Duration initialDelay,

            @DefaultValue("30s") @NotNull @DurationMin(millis = 100)
            Duration interval,

            @DefaultValue("100") @Min(1) @Max(1000) int batchSize) {}
}
