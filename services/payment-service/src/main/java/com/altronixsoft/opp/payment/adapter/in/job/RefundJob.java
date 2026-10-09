package com.altronixsoft.opp.payment.adapter.in.job;

import com.altronixsoft.opp.payment.application.CreateRefundsService;
import com.altronixsoft.opp.payment.application.RefundOutcome;
import com.altronixsoft.opp.payment.application.WorkBatchResult;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * The scheduled entry of the {@code RefundWorker}: runs one batch of {@link CreateRefundsService} and counts what
 * happened in {@code payment.refund.creation{outcome}}. The schedule lives in
 * {@code CancellationAndRefundJobsConfiguration}; tests and operators' tooling call {@link #run()} directly.
 */
public class RefundJob {

    static final String METRIC = "payment.refund.creation";

    private final CreateRefundsService service;
    private final MeterRegistry meters;

    public RefundJob(CreateRefundsService service, MeterRegistry meters) {
        this.service = service;
        this.meters = meters;
    }

    /** @return what the run did; empty if nothing was due */
    public WorkBatchResult<RefundOutcome> run() {
        WorkBatchResult<RefundOutcome> result = service.runBatch();
        WorkerMetrics.count(meters, METRIC, result, RefundOutcome.values());
        return result;
    }
}
