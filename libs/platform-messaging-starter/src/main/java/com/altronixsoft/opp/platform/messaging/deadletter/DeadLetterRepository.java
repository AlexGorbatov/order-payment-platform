package com.altronixsoft.opp.platform.messaging.deadletter;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** SQL access to {@code dead_letter_message}. All methods join the caller's transaction. */
public class DeadLetterRepository {

    private static final TypeReference<LinkedHashMap<String, String>> HEADERS_TYPE = new TypeReference<>() {};

    private static final String COLUMNS = """
            id, original_topic, dlt_topic, partition, "offset", original_partition, original_offset, message_key,
            payload, headers::text AS headers, exception_class, exception_message, status, note, created_at, updated_at
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json = JsonMapper.builder().build();
    private final RowMapper<DeadLetter> mapper = this::map;

    public DeadLetterRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Stores a dead letter; idempotent per record on the dead-letter topic.
     *
     * @return {@code true} if a row was inserted, {@code false} if this record was already persisted
     */
    public boolean insertIfAbsent(DeadLetter deadLetter) {
        return jdbc.sql("""
                                INSERT INTO dead_letter_message
                                    (original_topic, dlt_topic, partition, "offset", original_partition, original_offset,
                                     message_key, payload, headers, exception_class, exception_message)
                                VALUES
                                    (:originalTopic, :dltTopic, :partition, :offset, :originalPartition, :originalOffset,
                                     :messageKey, :payload, CAST(:headers AS jsonb), :exceptionClass, :exceptionMessage)
                                ON CONFLICT (dlt_topic, partition, "offset") DO NOTHING
                                """)
                        .param("originalTopic", deadLetter.originalTopic())
                        .param("dltTopic", deadLetter.dltTopic())
                        .param("partition", deadLetter.partition())
                        .param("offset", deadLetter.offset())
                        .param("originalPartition", deadLetter.originalPartition())
                        .param("originalOffset", deadLetter.originalOffset())
                        .param("messageKey", deadLetter.messageKey())
                        .param("payload", deadLetter.payload())
                        .param("headers", writeHeaders(deadLetter.headers()))
                        .param("exceptionClass", deadLetter.exceptionClass())
                        .param("exceptionMessage", deadLetter.exceptionMessage())
                        .update()
                == 1;
    }

    public Optional<DeadLetter> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM dead_letter_message WHERE id = :id")
                .param("id", id)
                .query(mapper)
                .optional();
    }

    /** Loads and row-locks a dead letter, so concurrent replay/resolve calls on it are serialized. */
    public Optional<DeadLetter> findByIdForUpdate(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM dead_letter_message WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(mapper)
                .optional();
    }

    /** Newest first. {@code status} and {@code originalTopic} are optional filters. */
    public List<DeadLetter> find(DeadLetterStatus status, String originalTopic, int limit, long offset) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> params = filterParams(status, originalTopic, where);
        JdbcClient.StatementSpec spec = jdbc.sql("SELECT " + COLUMNS + " FROM dead_letter_message" + where
                + " ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset");
        params.forEach(spec::param);
        return spec.param("limit", limit).param("offset", offset).query(mapper).list();
    }

    public long count(DeadLetterStatus status, String originalTopic) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> params = filterParams(status, originalTopic, where);
        JdbcClient.StatementSpec spec = jdbc.sql("SELECT count(*) FROM dead_letter_message" + where);
        params.forEach(spec::param);
        return spec.query(Long.class).single();
    }

    public void updateStatus(UUID id, DeadLetterStatus status, String note) {
        jdbc.sql("""
                        UPDATE dead_letter_message
                        SET status = :status, note = COALESCE(:note, note), updated_at = now()
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("status", status.name())
                .param("note", note)
                .update();
    }

    private static Map<String, Object> filterParams(
            DeadLetterStatus status, String originalTopic, StringBuilder where) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (status != null) {
            where.append(" AND status = :status");
            params.put("status", status.name());
        }
        if (originalTopic != null && !originalTopic.isBlank()) {
            where.append(" AND original_topic = :originalTopic");
            params.put("originalTopic", originalTopic);
        }
        return params;
    }

    private DeadLetter map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new DeadLetter(
                rs.getObject("id", UUID.class),
                rs.getString("original_topic"),
                rs.getString("dlt_topic"),
                rs.getInt("partition"),
                rs.getLong("offset"),
                rs.getObject("original_partition", Integer.class),
                rs.getObject("original_offset", Long.class),
                rs.getString("message_key"),
                rs.getBytes("payload"),
                readHeaders(rs.getString("headers")),
                rs.getString("exception_class"),
                rs.getString("exception_message"),
                DeadLetterStatus.valueOf(rs.getString("status")),
                rs.getString("note"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private String writeHeaders(Map<String, String> headers) {
        try {
            return json.writeValueAsString(headers);
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialize dead-letter headers", e);
        }
    }

    private Map<String, String> readHeaders(String headers) {
        try {
            return json.readValue(headers, HEADERS_TYPE);
        } catch (JacksonException e) {
            throw new IllegalStateException("Corrupt dead-letter headers: " + headers, e);
        }
    }
}
