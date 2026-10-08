package com.altronixsoft.opp.platform.messaging.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Outbox metrics (architecture §13):
 *
 * <ul>
 *   <li>{@code outbox.pending} (gauge) — unpublished rows;
 *   <li>{@code outbox.oldest.age.seconds} (gauge) — age of the oldest unpublished row, 0 when none; the primary alert
 *       signal, because it grows whether the relay is slow, failing or not running at all;
 *   <li>{@code outbox.publish.success} / {@code outbox.publish.failure} (counters, tag {@code topic});
 *   <li>{@code outbox.publish.latency} (timer, tag {@code topic}) — time from handing a record to Kafka until its
 *       acknowledgement, successful sends only;
 *   <li>{@code outbox.cleanup.deleted} (counter) — published rows removed by retention.
 * </ul>
 *
 * The gauges are backed by one cached database query (see {@code platform.outbox.metrics.cache-ttl}), so scraping
 * does not hit the database more often than that.
 */
public final class OutboxMetrics {

    private static final Logger log = LoggerFactory.getLogger(OutboxMetrics.class);

    private final MeterRegistry registry;
    private final OutboxRepository repository;
    private final long cacheTtlNanos;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(new Snapshot(0, 0, Long.MIN_VALUE, false));

    public OutboxMetrics(MeterRegistry registry, OutboxRepository repository, Duration cacheTtl) {
        this.registry = registry;
        this.repository = repository;
        this.cacheTtlNanos = cacheTtl.toNanos();
        Gauge.builder("outbox.pending", this, m -> m.current().pending())
                .description("Outbox rows not yet acknowledged by Kafka")
                .register(registry);
        Gauge.builder("outbox.oldest.age.seconds", this, m -> m.current().oldestAgeSeconds())
                .baseUnit("seconds")
                .description("Age of the oldest unpublished outbox row")
                .register(registry);
    }

    void recordSuccess(String topic, Duration latency) {
        registry.counter("outbox.publish.success", "topic", topic).increment();
        Timer.builder("outbox.publish.latency")
                .description("Time from send to broker acknowledgement")
                .tag("topic", topic)
                .register(registry)
                .record(latency);
    }

    void recordFailure(String topic) {
        registry.counter("outbox.publish.failure", "topic", topic).increment();
    }

    void recordCleanup(int deleted) {
        registry.counter("outbox.cleanup.deleted").increment(deleted);
    }

    private Snapshot current() {
        Snapshot last = snapshot.get();
        long now = System.nanoTime();
        if (last.loaded() && now - last.takenAtNanos() < cacheTtlNanos) {
            return last;
        }
        try {
            OutboxRepository.PendingStats stats = repository.pendingStats();
            Snapshot fresh = new Snapshot(stats.pending(), stats.oldestAgeSeconds(), now, true);
            snapshot.set(fresh);
            return fresh;
        } catch (RuntimeException e) {
            log.debug("Cannot refresh outbox gauges, serving the previous value", e);
            return last;
        }
    }

    private record Snapshot(double pending, double oldestAgeSeconds, long takenAtNanos, boolean loaded) {}
}
