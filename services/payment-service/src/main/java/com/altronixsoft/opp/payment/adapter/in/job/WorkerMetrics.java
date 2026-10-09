package com.altronixsoft.opp.payment.adapter.in.job;

import com.altronixsoft.opp.payment.application.WorkBatchResult;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Turns a worker run into {@code <metric>{outcome}} counters and one INFO line when the run did anything. */
final class WorkerMetrics {

    private static final Logger log = LoggerFactory.getLogger(WorkerMetrics.class);

    private WorkerMetrics() {}

    static <O extends Enum<O>> void count(
            MeterRegistry meters, String metric, WorkBatchResult<O> result, O[] outcomes) {
        for (O outcome : outcomes) {
            int count = result.count(outcome);
            if (count > 0) {
                meters.counter(metric, "outcome", outcome.name().toLowerCase(Locale.ROOT))
                        .increment(count);
            }
        }
        if (result.total() > 0) {
            log.info("{} run: {}", metric, result.counts());
        }
    }
}
