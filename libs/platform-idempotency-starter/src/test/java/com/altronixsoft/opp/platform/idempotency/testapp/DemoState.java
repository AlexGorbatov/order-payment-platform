package com.altronixsoft.opp.platform.idempotency.testapp;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/** How often each demo endpoint's business logic really ran. */
@Component
public class DemoState {

    private final Map<String, AtomicInteger> executions = new ConcurrentHashMap<>();

    int executed(String endpoint) {
        return executions.computeIfAbsent(endpoint, k -> new AtomicInteger()).incrementAndGet();
    }

    public int executions(String endpoint) {
        AtomicInteger count = executions.get(endpoint);
        return count == null ? 0 : count.get();
    }

    public void reset() {
        executions.clear();
    }
}
