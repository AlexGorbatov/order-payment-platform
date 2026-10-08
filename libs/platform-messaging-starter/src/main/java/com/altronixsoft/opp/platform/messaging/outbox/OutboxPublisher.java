package com.altronixsoft.opp.platform.messaging.outbox;

import com.altronixsoft.opp.contracts.EventEnvelope;
import com.altronixsoft.opp.contracts.EventSerde;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Writes events to the outbox in the caller's transaction (architecture §7.1).
 *
 * <p>The event becomes visible to {@link OutboxRelay} only when the business transaction commits, and disappears with
 * it on rollback, so a state change and its event are never separated. There is deliberately no way to publish
 * outside a transaction.
 */
public class OutboxPublisher {

    /** Kafka header names written with every event; consumers and tracing rely on them (architecture §9.2). */
    public static final String HEADER_EVENT_TYPE = "eventType";

    public static final String HEADER_EVENT_VERSION = "eventVersion";
    public static final String HEADER_CORRELATION_ID = "correlationId";
    public static final String HEADER_TRACEPARENT = "traceparent";

    private final OutboxRepository repository;
    private final EventSerde serde;
    private final TraceparentProvider traceparent;

    public OutboxPublisher(OutboxRepository repository, EventSerde serde, TraceparentProvider traceparent) {
        this.repository = repository;
        this.serde = serde;
        this.traceparent = traceparent;
    }

    /**
     * Stores {@code envelope} for delivery to {@code topic}. The Kafka key is the envelope's {@code partitionKey}; the
     * record value is the serialized envelope; headers carry {@code eventType}, {@code eventVersion},
     * {@code correlationId} and the {@code traceparent} of the current span (when tracing is active).
     *
     * @throws IllegalTransactionStateException when called without an active transaction
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(EventEnvelope<?> envelope, String topic) {
        Objects.requireNonNull(envelope, "envelope");
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be blank");
        }
        // The annotation enforces this through the Spring proxy; the explicit check also protects a bean that was
        // instantiated without one.
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException(
                    "OutboxPublisher.publish requires an active transaction (event " + envelope.eventType() + ")");
        }
        repository.insert(envelope, topic, serde.toJson(envelope), headersOf(envelope));
    }

    private Map<String, String> headersOf(EventEnvelope<?> envelope) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(HEADER_EVENT_TYPE, envelope.eventType());
        headers.put(HEADER_EVENT_VERSION, Integer.toString(envelope.eventVersion()));
        headers.put(HEADER_CORRELATION_ID, envelope.correlationId().toString());
        traceparent.current().ifPresent(value -> headers.put(HEADER_TRACEPARENT, value));
        return headers;
    }
}
