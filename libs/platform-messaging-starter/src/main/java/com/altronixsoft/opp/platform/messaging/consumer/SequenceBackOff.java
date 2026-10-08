package com.altronixsoft.opp.platform.messaging.consumer;

import java.time.Duration;
import java.util.List;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

/** A back-off with one explicit delay per attempt (for example 1 s, 10 s, 60 s), then stop. */
final class SequenceBackOff implements BackOff {

    private final long[] delaysMillis;

    SequenceBackOff(List<Duration> delays) {
        this.delaysMillis = delays.stream().mapToLong(Duration::toMillis).toArray();
    }

    @Override
    public BackOffExecution start() {
        return new BackOffExecution() {
            private int next;

            @Override
            public long nextBackOff() {
                return next < delaysMillis.length ? delaysMillis[next++] : STOP;
            }
        };
    }
}
