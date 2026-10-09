package com.altronixsoft.opp.payment.adapter.in.job;

import com.altronixsoft.opp.payment.application.InitiatePaymentsService;
import com.altronixsoft.opp.payment.application.InitiationBatchResult;
import com.altronixsoft.opp.payment.application.InitiationOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The scheduled entry of the {@code PaymentInitiationWorker}: runs one batch of {@link InitiatePaymentsService} and counts
 * what happened in {@code payment.initiation{outcome}}. The schedule lives in {@code InitiationJobConfiguration}; tests and
 * operators' tooling call {@link #run()} directly.
 */
public class PaymentInitiationJob {

    static final String METRIC = "payment.initiation";

    private static final Logger log = LoggerFactory.getLogger(PaymentInitiationJob.class);

    private final InitiatePaymentsService service;
    private final MeterRegistry meters;

    public PaymentInitiationJob(InitiatePaymentsService service, MeterRegistry meters) {
        this.service = service;
        this.meters = meters;
    }

    /** @return what the run did; empty if nothing was due */
    public InitiationBatchResult run() {
        InitiationBatchResult result = service.runBatch();
        for (InitiationOutcome outcome : InitiationOutcome.values()) {
            int count = result.count(outcome);
            if (count > 0) {
                meters.counter(METRIC, "outcome", outcome.name().toLowerCase(Locale.ROOT))
                        .increment(count);
            }
        }
        if (result.total() > 0) {
            log.info("Payment initiation run: {}", result.counts());
        }
        return result;
    }
}
