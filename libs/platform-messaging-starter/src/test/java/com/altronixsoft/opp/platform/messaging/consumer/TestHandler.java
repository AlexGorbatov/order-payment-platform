package com.altronixsoft.opp.platform.messaging.consumer;

import com.altronixsoft.opp.contracts.DomainEvent;
import com.altronixsoft.opp.contracts.EventEnvelope;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The business logic of the test consumer: one row in {@code demo_effect} per execution, with failures that the test
 * controls. Counters live in memory, so they also count executions whose transaction rolled back.
 */
public class TestHandler {

    /** A crash simulated after the business transaction committed. */
    public static class SimulatedCrash extends RuntimeException {

        private static final long serialVersionUID = 1L;

        SimulatedCrash(String message) {
            super(message);
        }
    }

    private final JdbcClient jdbc;
    private final Map<UUID, AtomicInteger> invocations = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> failuresLeft = new ConcurrentHashMap<>();
    private final Set<UUID> crashAfterCommit = ConcurrentHashMap.newKeySet();
    private volatile boolean failEverything;
    private volatile boolean failNonRetryable;
    private volatile Map<String, String> lastHeaders = Map.of();

    public TestHandler(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void handle(EventEnvelope<? extends DomainEvent> envelope) {
        UUID id = envelope.eventId();
        invocations.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
        if (failNonRetryable) {
            throw new NonRetryableEventException("business rule rejects " + id);
        }
        if (failEverything) {
            throw new IllegalStateException("boom: cannot process " + id);
        }
        AtomicInteger left = failuresLeft.get(id);
        if (left != null && left.getAndDecrement() > 0) {
            throw new IllegalStateException("transient failure for " + id);
        }
        jdbc.sql("INSERT INTO demo_effect (event_id) VALUES (:id)")
                .param("id", id)
                .update();
    }

    /** Called by the listener after the inbox transaction returned; throws once if a crash was requested. */
    void afterCommit(UUID eventId) {
        if (crashAfterCommit.remove(eventId)) {
            throw new SimulatedCrash("crash after commit, before the offset was committed: " + eventId);
        }
    }

    void remember(Map<String, String> headers) {
        this.lastHeaders = headers;
    }

    public void reset() {
        invocations.clear();
        failuresLeft.clear();
        crashAfterCommit.clear();
        failEverything = false;
        failNonRetryable = false;
        lastHeaders = Map.of();
    }

    public int invocations(UUID eventId) {
        AtomicInteger count = invocations.get(eventId);
        return count == null ? 0 : count.get();
    }

    public void failTimes(UUID eventId, int times) {
        failuresLeft.put(eventId, new AtomicInteger(times));
    }

    public void failEverything(boolean fail) {
        this.failEverything = fail;
    }

    public void failNonRetryable(boolean fail) {
        this.failNonRetryable = fail;
    }

    public void crashAfterCommitOnce(UUID eventId) {
        crashAfterCommit.add(eventId);
    }

    public Map<String, String> lastHeaders() {
        return lastHeaders;
    }

    public int effects(UUID eventId) {
        return jdbc.sql("SELECT count(*) FROM demo_effect WHERE event_id = :id")
                .param("id", eventId)
                .query(Integer.class)
                .single();
    }
}
