package com.altronixsoft.opp.platform.messaging.inbox;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Idempotent-consumer guard (architecture §7.2, ADR-0005).
 *
 * <p>{@link #executeOnce} inserts {@code (consumerGroup, eventId)} into {@code inbox_message} and runs the business
 * change in <b>one transaction</b>:
 *
 * <ul>
 *   <li>first delivery — the row is inserted, the action runs, both commit together;
 *   <li>redelivery after a commit — the insert finds the row, the action is <b>not</b> called, the duplicate is counted
 *       ({@code inbox.duplicates}) and the caller acknowledges the record normally;
 *   <li>failure — the action throws, the transaction rolls back <b>including the inbox row</b>, so the next delivery
 *       (retry, retry topic or a replay from the DLT) runs the action again.
 * </ul>
 *
 * Because the row and the business change commit or roll back together, a crash between the commit and the offset
 * commit (F03) only causes a harmless duplicate, and a message that was dead-lettered is never "already processed".
 *
 * <p>Call it from the Kafka listener, with the same {@code consumerGroup} as the listener's group id. If a transaction
 * is already active it joins it.
 */
public class InboxGuard {

    private static final Logger log = LoggerFactory.getLogger(InboxGuard.class);

    private final InboxRepository repository;
    private final TransactionOperations transactions;
    private final MeterRegistry meters;

    public InboxGuard(InboxRepository repository, TransactionOperations transactions, MeterRegistry meters) {
        this.repository = repository;
        this.transactions = transactions;
        this.meters = meters;
    }

    /**
     * Runs {@code action} unless this {@code (consumerGroup, eventId)} was already processed.
     *
     * @return {@code true} if the action ran, {@code false} if the event was a duplicate
     * @throws RuntimeException whatever {@code action} throws; the inbox row is rolled back with the action
     */
    public boolean executeOnce(String consumerGroup, UUID eventId, Runnable action) {
        Objects.requireNonNull(consumerGroup, "consumerGroup");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(action, "action");
        Boolean executed = transactions.execute(status -> {
            if (!repository.tryInsert(consumerGroup, eventId)) {
                return false;
            }
            action.run();
            return true;
        });
        if (!Boolean.TRUE.equals(executed)) {
            meters.counter("inbox.duplicates", "consumerGroup", consumerGroup).increment();
            log.debug("Skipping duplicate event {} for consumer group {}", eventId, consumerGroup);
            return false;
        }
        return true;
    }
}
