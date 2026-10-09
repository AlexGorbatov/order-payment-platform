package com.altronixsoft.opp.order.adapter.in.job;

import com.altronixsoft.opp.order.application.ExpireUnpaidOrdersService;
import com.altronixsoft.opp.order.application.IdGenerator;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Cancels orders that have been {@code PENDING_PAYMENT} for longer than the payment timeout (architecture §6.4).
 * Scheduled by the configuration; each batch is its own transaction, and a run continues until a batch comes back
 * short. Safe with several instances: batches are claimed with {@code FOR UPDATE SKIP LOCKED}.
 */
public class PaymentTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentTimeoutJob.class);

    private final ExpireUnpaidOrdersService service;
    private final IdGenerator ids;
    private final Duration paymentTimeout;
    private final int batchSize;

    public PaymentTimeoutJob(
            ExpireUnpaidOrdersService service, IdGenerator ids, Duration paymentTimeout, int batchSize) {
        this.service = service;
        this.ids = ids;
        this.paymentTimeout = paymentTimeout;
        this.batchSize = batchSize;
    }

    /**
     * One run. All orders cancelled in it share one correlation id: the run is the flow that cancelled them.
     *
     * @return how many orders were cancelled
     */
    public int run() {
        UUID correlationId = ids.newId();
        MDC.put("correlationId", correlationId.toString());
        try {
            int total = 0;
            int batch;
            do {
                batch = service.expireBatch(paymentTimeout, batchSize, correlationId);
                total += batch;
            } while (batch == batchSize);
            if (total > 0) {
                log.info("Cancelled {} order(s) not paid within {}", total, paymentTimeout);
            }
            return total;
        } finally {
            MDC.remove("correlationId");
        }
    }
}
