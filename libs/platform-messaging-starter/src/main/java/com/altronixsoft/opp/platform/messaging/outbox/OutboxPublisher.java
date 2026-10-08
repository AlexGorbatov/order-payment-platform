package com.altronixsoft.opp.platform.messaging.outbox;

import com.altronixsoft.opp.contracts.EventEnvelope;
import com.altronixsoft.opp.contracts.EventSerde;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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

    private static final JsonMapper JSON = JsonMapper.builder().build();

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

    /**
     * Stores an already serialized event envelope for delivery to {@code topic}, for example a dead letter that an
     * operator replays. Routing columns ({@code aggregateType}, {@code aggregateId}, {@code eventType},
     * {@code eventVersion}) are read from the JSON; the payload is stored and later sent unchanged, so unknown fields
     * survive. The outbox row gets a <b>new</b> id, while the envelope keeps its original {@code eventId}: the consumer
     * inbox deduplicates on the envelope, and two replays of the same event must not collide on the outbox primary key.
     *
     * @param headers Kafka headers to send; {@code eventType}, {@code eventVersion} and {@code correlationId} are filled
     *     in from the envelope when absent
     * @throws IllegalArgumentException when {@code envelopeJson} is not an event envelope
     * @throws IllegalTransactionStateException when called without an active transaction
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void republish(String topic, String partitionKey, String envelopeJson, Map<String, String> headers) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be blank");
        }
        if (partitionKey == null || partitionKey.isBlank()) {
            throw new IllegalArgumentException("partitionKey must not be blank");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException("OutboxPublisher.republish requires an active transaction");
        }
        JsonNode envelope = parseEnvelope(envelopeJson);
        // The record may have been produced without Kafka headers; the envelope always carries the same facts.
        Map<String, String> sendHeaders = new LinkedHashMap<>(headers);
        sendHeaders.putIfAbsent(HEADER_EVENT_TYPE, requiredText(envelope, "eventType"));
        sendHeaders.putIfAbsent(HEADER_EVENT_VERSION, Integer.toString(requiredInt(envelope, "eventVersion")));
        JsonNode correlationId = envelope.get("correlationId");
        if (correlationId != null && correlationId.isString()) {
            sendHeaders.putIfAbsent(HEADER_CORRELATION_ID, correlationId.stringValue());
        }
        repository.insertRaw(
                EventEnvelope.newEventId(),
                requiredText(envelope, "aggregateType"),
                requiredUuid(envelope, "aggregateId"),
                partitionKey,
                topic,
                requiredText(envelope, "eventType"),
                requiredInt(envelope, "eventVersion"),
                envelopeJson,
                sendHeaders);
    }

    private static JsonNode parseEnvelope(String envelopeJson) {
        try {
            JsonNode node = JSON.readTree(envelopeJson);
            if (!node.isObject()) {
                throw new IllegalArgumentException("payload is not a JSON object");
            }
            return node;
        } catch (JacksonException e) {
            throw new IllegalArgumentException("payload is not valid JSON: " + e.getOriginalMessage(), e);
        }
    }

    private static String requiredText(JsonNode envelope, String field) {
        JsonNode node = envelope.get(field);
        if (node == null || !node.isString() || node.stringValue().isBlank()) {
            throw new IllegalArgumentException("envelope field '" + field + "' is missing or not a text");
        }
        return node.stringValue();
    }

    private static UUID requiredUuid(JsonNode envelope, String field) {
        String value = requiredText(envelope, field);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("envelope field '" + field + "' is not a UUID: " + value, e);
        }
    }

    private static int requiredInt(JsonNode envelope, String field) {
        JsonNode node = envelope.get(field);
        if (node == null || !node.isInt() || node.intValue() < 1) {
            throw new IllegalArgumentException("envelope field '" + field + "' is missing or not a positive integer");
        }
        return node.intValue();
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
