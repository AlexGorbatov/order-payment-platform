package com.altronixsoft.opp.e2e.support;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * What a service has put on its outbox for an order: the events it published, which are exactly the events the other
 * service sees (the relay sends the stored envelope as is).
 */
public final class Outbox {

    private Outbox() {}

    /** Event types of the order, oldest first. */
    public static List<String> types(JdbcClient db, UUID orderId) {
        return db.sql("select event_type from outbox_event where partition_key = :key order by created_at, id")
                .param("key", orderId.toString())
                .query(String.class)
                .list();
    }

    /** How many events of {@code type} the order has. */
    public static long count(JdbcClient db, UUID orderId, String type) {
        return types(db, orderId).stream().filter(type::equals).count();
    }

    /** The {@code correlationId} of the envelope of the (first) event of {@code type}. */
    public static String correlationId(JdbcClient db, UUID orderId, String type) {
        return db.sql("select payload->>'correlationId' from outbox_event where partition_key = :key "
                        + "and event_type = :type order by created_at, id limit 1")
                .param("key", orderId.toString())
                .param("type", type)
                .query(String.class)
                .single();
    }

    /** A field of the payload of the (first) event of {@code type}, e.g. {@code reason} of OrderRefundRequested. */
    public static String payloadField(JdbcClient db, UUID orderId, String type, String field) {
        return db.sql("select payload->'payload'->>:field from outbox_event where partition_key = :key "
                        + "and event_type = :type order by created_at, id limit 1")
                .param("field", field)
                .param("key", orderId.toString())
                .param("type", type)
                .query(String.class)
                .single();
    }
}
