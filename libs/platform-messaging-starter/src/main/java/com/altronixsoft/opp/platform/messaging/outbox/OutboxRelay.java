package com.altronixsoft.opp.platform.messaging.outbox;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Polls the outbox and delivers unpublished rows to Kafka (architecture §7.1, ADR-0004).
 *
 * <p>One cycle runs in <b>one database transaction</b>: it claims a batch with
 * {@code SELECT … ORDER BY created_at, id LIMIT n FOR UPDATE SKIP LOCKED}, sends the rows one by one in order, waits for
 * the broker's acknowledgement of each, and marks it {@code published_at = now()}. A failed send records
 * {@code attempts + 1} and {@code last_error} and <b>stops the batch</b>, so a later row never overtakes an earlier one.
 *
 * <h2>Why sending inside a transaction that holds row locks is acceptable</h2>
 *
 * Normally a remote call must never run inside a database transaction. The relay is the sanctioned exception:
 *
 * <ul>
 *   <li>The locks are on outbox rows that <i>only the relay</i> touches. Business transactions only {@code INSERT}
 *       new rows and never wait for these locks, so a slow Kafka cannot block request handling.
 *   <li>The lock lasts at most one batch (bounded by {@code batch-size} × {@code ack-timeout}) and is held on a single
 *       short-lived connection per instance.
 *   <li>{@code SKIP LOCKED} makes additional instances take <i>other</i> rows instead of waiting, so the relay scales
 *       out without coordination. Per-key order across instances is preserved by
 *       {@link OutboxRepository#keysBlockedByOlderRows}: an instance never sends a row while an older row of the same
 *       key is still held, uncommitted, by another instance.
 *   <li>Holding the lock until the acknowledgement is what makes the claim exclusive: no second instance can send the
 *       same row concurrently.
 * </ul>
 *
 * <h2>The risk and why it is covered</h2>
 *
 * If the process dies, or the commit fails, <b>after</b> Kafka acknowledged a record but <b>before</b> {@code
 * published_at} is committed, the row stays unpublished and is sent again: delivery is <b>at least once</b>
 * (architecture F02). The consumer's inbox ({@code (consumer_group, event_id)}, ADR-0005) discards the duplicate, so the
 * business effect happens once. This is the price of not coordinating PostgreSQL and Kafka in a single transaction.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository repository;
    private final OutboxSender sender;
    private final TransactionOperations transactions;
    private final OutboxMetrics metrics;
    private final int batchSize;

    public OutboxRelay(
            OutboxRepository repository,
            OutboxSender sender,
            TransactionOperations transactions,
            OutboxMetrics metrics,
            int batchSize) {
        this.repository = repository;
        this.sender = sender;
        this.transactions = transactions;
        this.metrics = metrics;
        this.batchSize = batchSize;
    }

    /** Scheduler entry point; never throws, so a failing cycle does not stop later ones. */
    @Scheduled(
            fixedDelayString = "${platform.outbox.relay.fixed-delay:500ms}",
            initialDelayString = "${platform.outbox.relay.initial-delay:1s}")
    public void poll() {
        try {
            pollOnce();
        } catch (RuntimeException e) {
            log.error("Outbox relay cycle failed; the claimed rows stay unpublished and will be retried", e);
        }
    }

    /**
     * Runs one cycle.
     *
     * @return the number of rows acknowledged by Kafka and marked published in this cycle
     */
    public int pollOnce() {
        Integer published = transactions.execute(status -> publishBatch());
        return published == null ? 0 : published;
    }

    private int publishBatch() {
        List<OutboxMessage> claimed = repository.claimBatch(batchSize);
        if (claimed.isEmpty()) {
            return 0;
        }
        Set<String> blockedKeys = repository.keysBlockedByOlderRows(
                claimed.stream().map(OutboxMessage::id).toList());
        int published = 0;
        for (OutboxMessage message : claimed) {
            if (blockedKeys.contains(message.partitionKey())) {
                continue;
            }
            long startedAt = System.nanoTime();
            try {
                sender.send(message);
            } catch (RuntimeException e) {
                recordFailure(message, e);
                break;
            }
            metrics.recordSuccess(message.topic(), Duration.ofNanos(System.nanoTime() - startedAt));
            repository.markPublished(message.id());
            published++;
        }
        return published;
    }

    private void recordFailure(OutboxMessage message, RuntimeException failure) {
        UUID id = message.id();
        repository.markFailed(id, failure.getMessage());
        metrics.recordFailure(message.topic());
        log.warn(
                "Outbox send failed for event {} on {} (attempt {}); stopping the batch to keep per-key order: {}",
                id,
                message.topic(),
                message.attempts() + 1,
                failure.getMessage());
    }
}
