package com.altronixsoft.opp.platform.messaging.outbox;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Deletes published outbox rows older than the retention (default 7 days, architecture §7.1). Unpublished rows are never
 * deleted, whatever their age. Deletes in small batches, each in its own transaction, so the table is never locked for
 * long. Safe to run on several instances at once.
 */
public class OutboxCleanup {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleanup.class);

    private final OutboxRepository repository;
    private final TransactionOperations transactions;
    private final OutboxMetrics metrics;
    private final Duration retention;
    private final int batchSize;

    public OutboxCleanup(
            OutboxRepository repository,
            TransactionOperations transactions,
            OutboxMetrics metrics,
            Duration retention,
            int batchSize) {
        this.repository = repository;
        this.transactions = transactions;
        this.metrics = metrics;
        this.retention = retention;
        this.batchSize = batchSize;
    }

    /** Scheduler entry point; never throws. */
    @Scheduled(
            fixedDelayString = "${platform.outbox.cleanup.fixed-delay:1h}",
            initialDelayString = "${platform.outbox.cleanup.fixed-delay:1h}")
    public void run() {
        try {
            int deleted = cleanUp();
            if (deleted > 0) {
                log.info("Outbox cleanup removed {} published rows older than {}", deleted, retention);
            }
        } catch (RuntimeException e) {
            log.error("Outbox cleanup failed; it will run again on schedule", e);
        }
    }

    /** Deletes all expired published rows; returns how many. */
    public int cleanUp() {
        int total = 0;
        int deleted;
        do {
            Integer count = transactions.execute(status -> repository.deletePublishedOlderThan(retention, batchSize));
            deleted = count == null ? 0 : count;
            total += deleted;
        } while (deleted == batchSize);
        if (total > 0) {
            metrics.recordCleanup(total);
        }
        return total;
    }
}
