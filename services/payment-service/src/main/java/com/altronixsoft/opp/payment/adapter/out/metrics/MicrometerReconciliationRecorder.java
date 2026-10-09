package com.altronixsoft.opp.payment.adapter.out.metrics;

import com.altronixsoft.opp.payment.application.ReconciliationRecorder;
import com.altronixsoft.opp.payment.application.ReconciliationSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * {@link ReconciliationRecorder} as Micrometer meters (architecture §13): {@code reconciliation.checked},
 * {@code reconciliation.drift{from,to}} (alert on any increase: a webhook was lost or late),
 * {@code reconciliation.failures} and {@code reconciliation.deferred}.
 */
@Component
class MicrometerReconciliationRecorder implements ReconciliationRecorder {

    private final MeterRegistry meters;

    MicrometerReconciliationRecorder(MeterRegistry meters) {
        this.meters = meters;
    }

    @Override
    public void record(ReconciliationSummary summary) {
        meters.counter("reconciliation.checked").increment(summary.checked());
        meters.counter("reconciliation.failures").increment(summary.failed());
        meters.counter("reconciliation.deferred").increment(summary.deferred());
        for (ReconciliationSummary.Drift drift : summary.drifts()) {
            meters.counter(
                            "reconciliation.drift",
                            "from",
                            drift.from().name(),
                            "to",
                            drift.to().name())
                    .increment();
        }
    }
}
