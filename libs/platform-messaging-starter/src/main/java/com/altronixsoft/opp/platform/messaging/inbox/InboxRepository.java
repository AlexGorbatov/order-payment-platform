package com.altronixsoft.opp.platform.messaging.inbox;

import java.time.Duration;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** SQL access to {@code inbox_message}. All methods join the caller's transaction. */
public class InboxRepository {

    private final JdbcClient jdbc;

    public InboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records that {@code (consumerGroup, eventId)} is being processed.
     *
     * @return {@code true} if the row was inserted (first time), {@code false} if it already existed (duplicate). When a
     *     concurrent transaction is inserting the same key, this call waits for its outcome.
     */
    public boolean tryInsert(String consumerGroup, UUID eventId) {
        return jdbc.sql("""
                        INSERT INTO inbox_message (consumer_group, event_id)
                        VALUES (:consumerGroup, :eventId)
                        ON CONFLICT DO NOTHING
                        """)
                        .param("consumerGroup", consumerGroup)
                        .param("eventId", eventId)
                        .update()
                == 1;
    }

    /** Deletes up to {@code limit} rows received longer than {@code retention} ago; returns how many. */
    public int deleteOlderThan(Duration retention, int limit) {
        return jdbc.sql("""
                        DELETE FROM inbox_message
                        WHERE (consumer_group, event_id) IN (
                            SELECT consumer_group, event_id
                            FROM inbox_message
                            WHERE received_at < now() - CAST(:retention AS interval)
                            LIMIT :limit)
                        """)
                .param("retention", retention.toString())
                .param("limit", limit)
                .update();
    }
}
