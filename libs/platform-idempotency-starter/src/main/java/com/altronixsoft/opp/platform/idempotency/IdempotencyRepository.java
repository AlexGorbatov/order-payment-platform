package com.altronixsoft.opp.platform.idempotency;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * SQL access to {@code idempotency_record}. <b>Every method runs in its own short transaction
 * ({@code REQUIRES_NEW})</b>, whatever transaction the caller is in: a claim must be committed — and so visible to
 * concurrent requests — before the business method starts, and the outcome must be recorded even when the request's
 * own transaction rolled back.
 */
public class IdempotencyRepository {

    private static final TypeReference<Map<String, List<String>>> HEADERS_TYPE = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final JsonMapper json = JsonMapper.builder().build();

    public IdempotencyRepository(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Tries to reserve {@code (principal, key)} for a request: inserts an {@code IN_PROGRESS} record, or takes over a
     * record that is expired or an abandoned claim (still {@code IN_PROGRESS} after {@code abandonedAfter}). Atomic; of
     * several concurrent callers exactly one wins.
     *
     * @return {@code true} if the caller now owns the key, {@code false} if a live record exists
     */
    public boolean claim(String principal, String key, String requestHash, Duration ttl, Duration abandonedAfter) {
        Integer claimed = transaction.execute(status -> jdbc.sql("""
                        INSERT INTO idempotency_record (principal, idem_key, request_hash, status, created_at, expires_at)
                        VALUES (:principal, :key, :hash, 'IN_PROGRESS', now(), now() + CAST(:ttl AS interval))
                        ON CONFLICT (principal, idem_key) DO UPDATE
                           SET request_hash = EXCLUDED.request_hash,
                               status = 'IN_PROGRESS',
                               response_status = NULL,
                               response_headers = NULL,
                               response_body = NULL,
                               created_at = now(),
                               expires_at = EXCLUDED.expires_at
                         WHERE idempotency_record.expires_at <= now()
                            OR (idempotency_record.status = 'IN_PROGRESS'
                                AND idempotency_record.created_at <= now() - CAST(:abandoned AS interval))
                        """)
                .param("principal", principal)
                .param("key", key)
                .param("hash", requestHash)
                .param("ttl", ttl.toString())
                .param("abandoned", abandonedAfter.toString())
                .update());
        return claimed != null && claimed == 1;
    }

    public Optional<IdempotencyRecord> find(String principal, String key) {
        return transaction.execute(status -> jdbc.sql("""
                        SELECT status, request_hash, response_status, response_headers::text AS response_headers,
                               response_body
                        FROM idempotency_record
                        WHERE principal = :principal AND idem_key = :key
                        """)
                .param("principal", principal)
                .param("key", key)
                .query((rs, rowNum) -> new IdempotencyRecord(
                        IdempotencyRecord.Status.valueOf(rs.getString("status")),
                        rs.getString("request_hash"),
                        rs.getObject("response_status", Integer.class),
                        readHeaders(rs.getString("response_headers")),
                        rs.getBytes("response_body")))
                .optional());
    }

    /** Stores the response and marks the record {@code COMPLETED}. */
    public void complete(
            String principal, String key, int responseStatus, Map<String, List<String>> headers, byte[] body) {
        transaction.executeWithoutResult(status -> jdbc.sql("""
                        UPDATE idempotency_record
                        SET status = 'COMPLETED', response_status = :status,
                            response_headers = CAST(:headers AS jsonb), response_body = :body
                        WHERE principal = :principal AND idem_key = :key AND status = 'IN_PROGRESS'
                        """)
                .param("principal", principal)
                .param("key", key)
                .param("status", responseStatus)
                .param("headers", writeHeaders(headers))
                .param("body", body)
                .update());
    }

    /** Removes the caller's own {@code IN_PROGRESS} record, so the client may retry. A completed record stays. */
    public void release(String principal, String key) {
        transaction.executeWithoutResult(status ->
                jdbc.sql("""
                        DELETE FROM idempotency_record
                        WHERE principal = :principal AND idem_key = :key AND status = 'IN_PROGRESS'
                        """).param("principal", principal).param("key", key).update());
    }

    /** Deletes up to {@code limit} expired records; returns how many. */
    public int deleteExpired(int limit) {
        Integer deleted = transaction.execute(
                status -> jdbc.sql("""
                        DELETE FROM idempotency_record
                        WHERE (principal, idem_key) IN (
                            SELECT principal, idem_key FROM idempotency_record WHERE expires_at <= now() LIMIT :limit)
                        """).param("limit", limit).update());
        return deleted == null ? 0 : deleted;
    }

    private String writeHeaders(Map<String, List<String>> headers) {
        try {
            return json.writeValueAsString(headers);
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialize response headers", e);
        }
    }

    private Map<String, List<String>> readHeaders(String headers) {
        if (headers == null) {
            return null;
        }
        try {
            return json.readValue(headers, HEADERS_TYPE);
        } catch (JacksonException e) {
            throw new IllegalStateException("Corrupt stored response headers: " + headers, e);
        }
    }
}
