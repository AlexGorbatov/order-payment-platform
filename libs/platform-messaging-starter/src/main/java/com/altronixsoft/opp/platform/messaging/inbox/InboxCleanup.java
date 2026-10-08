package com.altronixsoft.opp.platform.messaging.inbox;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Deletes inbox rows older than the retention (default 14 days, architecture §7.2). The retention must stay longer than
 * the topic retention, otherwise a very late redelivery would no longer be recognised as a duplicate. Deletes in small
 * batches, each in its own transaction. Safe to run on several instances at once.
 */
public class InboxCleanup {

    private static final Logger log = LoggerFactory.getLogger(InboxCleanup.class);

    private final InboxRepository repository;
    private final TransactionOperations transactions;
    private final Duration retention;
    private final int batchSize;

    public InboxCleanup(
            InboxRepository repository, TransactionOperations transactions, Duration retention, int batchSize) {
        this.repository = repository;
        this.transactions = transactions;
        this.retention = retention;
        this.batchSize = batchSize;
    }

    /** Scheduler entry point; never throws. */
    @Scheduled(
            fixedDelayString = "${platform.inbox.cleanup.fixed-delay:1h}",
            initialDelayString = "${platform.inbox.cleanup.fixed-delay:1h}")
    public void run() {
        try {
            int deleted = cleanUp();
            if (deleted > 0) {
                log.info("Inbox cleanup removed {} rows older than {}", deleted, retention);
            }
        } catch (RuntimeException e) {
            log.error("Inbox cleanup failed; it will run again on schedule", e);
        }
    }

    /** Deletes all expired rows; returns how many. */
    public int cleanUp() {
        int total = 0;
        int deleted;
        do {
            Integer count = transactions.execute(status -> repository.deleteOlderThan(retention, batchSize));
            deleted = count == null ? 0 : count;
            total += deleted;
        } while (deleted == batchSize);
        return total;
    }
}
