package com.altronixsoft.opp.payment.adapter.in.job;

import com.altronixsoft.opp.payment.application.ReconcilePaymentsService;
import com.altronixsoft.opp.payment.application.ReconciliationSummary;

/**
 * The scheduled entry of reconciliation (architecture §8.4): one {@link ReconcilePaymentsService} run every
 * {@code payment.reconciliation.interval} (5 minutes). The schedule lives in {@code ReconciliationConfiguration}; an
 * operator runs it at once with {@code POST /admin/reconciliation/run}.
 */
public class ReconciliationJob {

    private final ReconcilePaymentsService service;

    public ReconciliationJob(ReconcilePaymentsService service) {
        this.service = service;
    }

    public ReconciliationSummary run() {
        return service.run(ReconciliationSummary.Trigger.SCHEDULED);
    }
}
