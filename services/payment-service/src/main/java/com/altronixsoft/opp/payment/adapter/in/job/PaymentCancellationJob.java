package com.altronixsoft.opp.payment.adapter.in.job;

import com.altronixsoft.opp.payment.application.CancelPaymentIntentsService;
import com.altronixsoft.opp.payment.application.CancellationOutcome;
import com.altronixsoft.opp.payment.application.WorkBatchResult;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * The scheduled entry of the {@code PaymentCancellationWorker}: runs one batch of {@link CancelPaymentIntentsService} and
 * counts what happened in {@code payment.cancellation{outcome}}. The schedule lives in
 * {@code CancellationAndRefundJobsConfiguration}; tests and operators' tooling call {@link #run()} directly.
 */
public class PaymentCancellationJob {

    static final String METRIC = "payment.cancellation";

    private final CancelPaymentIntentsService service;
    private final MeterRegistry meters;

    public PaymentCancellationJob(CancelPaymentIntentsService service, MeterRegistry meters) {
        this.service = service;
        this.meters = meters;
    }

    /** @return what the run did; empty if nothing was due */
    public WorkBatchResult<CancellationOutcome> run() {
        WorkBatchResult<CancellationOutcome> result = service.runBatch();
        WorkerMetrics.count(meters, METRIC, result, CancellationOutcome.values());
        return result;
    }
}
