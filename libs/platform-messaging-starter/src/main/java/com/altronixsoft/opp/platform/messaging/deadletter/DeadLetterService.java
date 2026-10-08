package com.altronixsoft.opp.platform.messaging.deadletter;

import com.altronixsoft.opp.platform.messaging.outbox.OutboxPublisher;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Operator actions on dead letters (architecture §7.4, ADR-0007): list, inspect, replay, resolve.
 *
 * <h2>Replay</h2>
 *
 * Replay re-publishes the stored payload, unchanged, to the <b>original topic</b> <i>through the outbox</i> and marks the
 * dead letter {@code REPLAYED}, both in one transaction: either the event is queued and the status changes, or neither
 * happens. The relay then delivers it with the usual guarantees. Headers {@code eventType}, {@code eventVersion},
 * {@code correlationId} and {@code traceparent} are carried over; {@code x-replay-of} holds the dead letter id.
 *
 * <p>The replayed record has the same envelope {@code eventId} as the one that failed, and it is <b>not</b> rejected by
 * the inbox: the failed processing threw, its transaction rolled back, and the inbox row — inserted in that very
 * transaction — went with it. "No inbox row" is exactly the state of an event that was never processed. (A dead letter
 * whose event was in fact processed, for example a duplicate that failed later, is correctly skipped as a duplicate.)
 *
 * <p>Replay is allowed once per dead letter; a second attempt, or one on a resolved dead letter, is a conflict.
 */
public class DeadLetterService {

    /** Header that marks a replayed record; its value is the dead letter id. */
    public static final String HEADER_REPLAY_OF = "x-replay-of";

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);

    /** Business headers that survive a replay; Kafka-internal and exception headers do not. */
    private static final List<String> REPLAYED_HEADERS =
            List.of("eventType", "eventVersion", "correlationId", "traceparent");

    private final DeadLetterRepository repository;
    private final OutboxPublisher outbox;
    private final TransactionOperations transactions;

    public DeadLetterService(
            DeadLetterRepository repository, OutboxPublisher outbox, TransactionOperations transactions) {
        this.repository = repository;
        this.outbox = outbox;
        this.transactions = transactions;
    }

    public Page list(DeadLetterStatus status, String originalTopic, int page, int size) {
        List<DeadLetter> items = repository.find(status, originalTopic, size, (long) page * size);
        return new Page(items, repository.count(status, originalTopic));
    }

    public DeadLetter get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new DeadLetterException.NotFound(id));
    }

    /**
     * Queues the dead letter for re-delivery to its original topic.
     *
     * @throws DeadLetterException.NotFound unknown id
     * @throws DeadLetterException.InvalidState already replayed or resolved
     * @throws DeadLetterException.NotReplayable the payload is not an event envelope
     */
    public DeadLetter replay(UUID id) {
        DeadLetter replayed = transactions.execute(status -> {
            DeadLetter deadLetter =
                    repository.findByIdForUpdate(id).orElseThrow(() -> new DeadLetterException.NotFound(id));
            if (deadLetter.status() != DeadLetterStatus.NEW) {
                throw new DeadLetterException.InvalidState(id, deadLetter.status(), "replayed");
            }
            publish(deadLetter);
            repository.updateStatus(id, DeadLetterStatus.REPLAYED, null);
            return deadLetter;
        });
        log.info("Replayed dead letter {} to {}", id, replayed.originalTopic());
        return get(id);
    }

    /**
     * Closes the dead letter with an operator comment.
     *
     * @throws DeadLetterException.NotFound unknown id
     * @throws DeadLetterException.InvalidState already resolved
     */
    public DeadLetter resolve(UUID id, String comment) {
        transactions.executeWithoutResult(status -> {
            DeadLetter deadLetter =
                    repository.findByIdForUpdate(id).orElseThrow(() -> new DeadLetterException.NotFound(id));
            if (deadLetter.status() == DeadLetterStatus.RESOLVED) {
                throw new DeadLetterException.InvalidState(id, deadLetter.status(), "resolved again");
            }
            repository.updateStatus(id, DeadLetterStatus.RESOLVED, comment);
        });
        log.info("Resolved dead letter {}", id);
        return get(id);
    }

    private void publish(DeadLetter deadLetter) {
        String payload = new String(deadLetter.payload(), StandardCharsets.UTF_8);
        String partitionKey = deadLetter.messageKey();
        if (partitionKey == null || partitionKey.isBlank()) {
            throw new DeadLetterException.NotReplayable(deadLetter.id(), "the record has no key", null);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : REPLAYED_HEADERS) {
            String value = deadLetter.headers().get(name);
            if (value != null) {
                headers.put(name, value);
            }
        }
        headers.put(HEADER_REPLAY_OF, deadLetter.id().toString());
        try {
            outbox.republish(deadLetter.originalTopic(), partitionKey, payload, headers);
        } catch (IllegalArgumentException e) {
            throw new DeadLetterException.NotReplayable(deadLetter.id(), e.getMessage(), e);
        }
    }

    /** One page of dead letters and the total number matching the filter. */
    public record Page(List<DeadLetter> items, long totalItems) {}
}
