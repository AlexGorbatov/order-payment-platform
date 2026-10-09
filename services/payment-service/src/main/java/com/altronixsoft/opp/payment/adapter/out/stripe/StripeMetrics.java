package com.altronixsoft.opp.payment.adapter.out.stripe;

import com.altronixsoft.opp.payment.application.GatewayErrorClass;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Metrics of the Stripe adapter.
 *
 * <ul>
 *   <li>{@code stripe.api.latency{operation, outcome}}: duration of a gateway call, including the SDK's own retries.
 *       {@code outcome} is {@code success}, {@code transient}, {@code permanent}, {@code config},
 *       {@code idempotency_mismatch} or {@code circuit_open}.
 *   <li>{@code stripe.api.errors{type}}: failed calls by the same classification.
 * </ul>
 */
final class StripeMetrics {

    static final String SUCCESS = "success";
    static final String CIRCUIT_OPEN = "circuit_open";

    private final MeterRegistry registry;

    StripeMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    long start() {
        return System.nanoTime();
    }

    void success(String operation, long startedAtNanos) {
        record(operation, SUCCESS, startedAtNanos);
    }

    void failure(String operation, GatewayErrorClass errorClass, boolean circuitOpen, long startedAtNanos) {
        String type = circuitOpen ? CIRCUIT_OPEN : errorClass.name().toLowerCase(Locale.ROOT);
        record(operation, type, startedAtNanos);
        Counter.builder("stripe.api.errors")
                .description("Failed calls to Stripe by classification")
                .tag("type", type)
                .register(registry)
                .increment();
    }

    private void record(String operation, String outcome, long startedAtNanos) {
        Timer.builder("stripe.api.latency")
                .description("Duration of a Stripe gateway call, SDK retries included")
                .tag("operation", operation)
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(registry)
                .record(System.nanoTime() - startedAtNanos, TimeUnit.NANOSECONDS);
    }
}
