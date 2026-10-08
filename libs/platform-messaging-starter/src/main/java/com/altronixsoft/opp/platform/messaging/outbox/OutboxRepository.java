package com.altronixsoft.opp.platform.messaging.outbox;

import com.altronixsoft.opp.contracts.EventEnvelope;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** SQL access to {@code outbox_event}. All methods join the caller's transaction. */
public class OutboxRepository {

    private static final TypeReference<LinkedHashMap<String, String>> HEADERS_TYPE = new TypeReference<>() {};
    private static final int MAX_ERROR_LENGTH = 1000;

    private final JdbcClient jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a row; {@code created_at} is assigned by the database. */
    public void insert(EventEnvelope<?> envelope, String topic, String envelopeJson, Map<String, String> headers) {
        jdbc.sql("""
                        INSERT INTO outbox_event
                            (id, aggregate_type, aggregate_id, partition_key, topic, event_type, event_version,
                             payload, headers)
                        VALUES
                            (:id, :aggregateType, :aggregateId, :partitionKey, :topic, :eventType, :eventVersion,
                             CAST(:payload AS jsonb), CAST(:headers AS jsonb))
                        """)
                .param("id", envelope.eventId())
                .param("aggregateType", envelope.aggregateType())
                .param("aggregateId", envelope.aggregateId())
                .param("partitionKey", envelope.partitionKey())
                .param("topic", topic)
                .param("eventType", envelope.eventType())
                .param("eventVersion", envelope.eventVersion())
                .param("payload", envelopeJson)
                .param("headers", writeHeaders(headers))
                .update();
    }

    /**
     * Locks up to {@code limit} unpublished rows in publishing order. Rows locked by another relay instance are
     * skipped, so concurrent relays never claim the same row.
     */
    public List<OutboxMessage> claimBatch(int limit) {
        return jdbc.sql("""
                        SELECT id, topic, partition_key, payload::text AS payload, headers::text AS headers, attempts
                        FROM outbox_event
                        WHERE published_at IS NULL
                        ORDER BY created_at, id
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("limit", limit)
                .query((rs, rowNum) -> new OutboxMessage(
                        rs.getObject("id", UUID.class),
                        rs.getString("topic"),
                        rs.getString("partition_key"),
                        rs.getString("payload"),
                        readHeaders(rs.getString("headers")),
                        rs.getInt("attempts")))
                .list();
    }

    /**
     * Returns the partition keys of the claimed rows that still have an older unpublished row this transaction does
     * not hold: another relay instance has claimed it and has not committed yet. Sending the newer row now would
     * overtake the older one, so the relay leaves these keys to the instance that owns the older row.
     */
    public Set<String> keysBlockedByOlderRows(List<UUID> claimedIds) {
        if (claimedIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(
                jdbc.sql("""
                        SELECT DISTINCT b.partition_key
                        FROM outbox_event b
                        WHERE b.id IN (:ids)
                          AND EXISTS (
                              SELECT 1
                              FROM outbox_event o
                              WHERE o.partition_key = b.partition_key
                                AND o.published_at IS NULL
                                AND (o.created_at, o.id) < (b.created_at, b.id)
                                AND o.id NOT IN (:ids))
                        """).param("ids", claimedIds).query(String.class).list());
    }

    public void markPublished(UUID id) {
        jdbc.sql("UPDATE outbox_event SET published_at = now() WHERE id = :id")
                .param("id", id)
                .update();
    }

    public void markFailed(UUID id, String error) {
        jdbc.sql("UPDATE outbox_event SET attempts = attempts + 1, last_error = :error WHERE id = :id")
                .param("id", id)
                .param("error", truncate(error))
                .update();
    }

    /** Number of unpublished rows and the age in seconds of the oldest one (0 when there are none). */
    public PendingStats pendingStats() {
        return jdbc.sql("""
                        SELECT count(*) AS pending,
                               COALESCE(EXTRACT(EPOCH FROM (now() - min(created_at))), 0) AS oldest_age_seconds
                        FROM outbox_event
                        WHERE published_at IS NULL
                        """)
                .query((rs, rowNum) -> new PendingStats(rs.getLong("pending"), rs.getDouble("oldest_age_seconds")))
                .single();
    }

    /** Deletes up to {@code limit} rows published longer than {@code retention} ago; returns how many. */
    public int deletePublishedOlderThan(Duration retention, int limit) {
        return jdbc.sql("""
                        DELETE FROM outbox_event
                        WHERE id IN (
                            SELECT id
                            FROM outbox_event
                            WHERE published_at IS NOT NULL
                              AND published_at < now() - CAST(:retention AS interval)
                            LIMIT :limit)
                        """)
                .param("retention", retention.toString())
                .param("limit", limit)
                .update();
    }

    private String writeHeaders(Map<String, String> headers) {
        try {
            return json.writeValueAsString(headers);
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialize outbox headers", e);
        }
    }

    private Map<String, String> readHeaders(String headers) {
        try {
            return json.readValue(headers, HEADERS_TYPE);
        } catch (JacksonException e) {
            throw new IllegalStateException("Corrupt outbox headers: " + headers, e);
        }
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }

    /** Snapshot for the backlog gauges. */
    public record PendingStats(long pending, double oldestAgeSeconds) {}
}
