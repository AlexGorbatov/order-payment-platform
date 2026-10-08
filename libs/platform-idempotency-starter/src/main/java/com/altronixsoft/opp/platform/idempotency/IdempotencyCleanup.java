package com.altronixsoft.opp.platform.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Deletes records whose idempotency window has ended ({@code expires_at}), in batches that each run in their own
 * transaction. Expired records are already ignored by the claim logic (the key may be reused); this only reclaims space.
 * Safe to run on several instances at once.
 */
public class IdempotencyCleanup {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanup.class);

    private final IdempotencyRepository repository;
    private final int batchSize;

    public IdempotencyCleanup(IdempotencyRepository repository, int batchSize) {
        this.repository = repository;
        this.batchSize = batchSize;
    }

    /** Scheduler entry point; never throws. */
    @Scheduled(
            fixedDelayString = "${platform.idempotency.cleanup.fixed-delay:10m}",
            initialDelayString = "${platform.idempotency.cleanup.fixed-delay:10m}")
    public void run() {
        try {
            int deleted = cleanUp();
            if (deleted > 0) {
                log.info("Idempotency cleanup removed {} expired records", deleted);
            }
        } catch (RuntimeException e) {
            log.error("Idempotency cleanup failed; it will run again on schedule", e);
        }
    }

    /** Deletes all expired records; returns how many. */
    public int cleanUp() {
        int total = 0;
        int deleted;
        do {
            deleted = repository.deleteExpired(batchSize);
            total += deleted;
        } while (deleted == batchSize);
        return total;
    }
}
