package com.altronixsoft.opp.order;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The real UTC clock plus an offset that a test can move forward, so that "30 minutes later" takes no time. The service
 * gets it instead of the system clock in the integration tests.
 */
final class MutableClock extends Clock {

    private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

    void advance(Duration duration) {
        offset.accumulateAndGet(duration, Duration::plus);
    }

    void reset() {
        offset.set(Duration.ZERO);
    }

    @Override
    public Instant instant() {
        return Instant.now().plus(offset.get());
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("the service works in UTC");
    }
}
