package com.altronixsoft.opp.platform.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OutboxCleanupAndMetricsIT extends AbstractRelayDisabledIT {

    @Autowired
    SimpleMeterRegistry meters;

    @Test
    void cleanupDeletesOnlyPublishedRowsOlderThanTheRetention() {
        String topic = "cleanup-it";
        UUID tooOld1 = publish(topic);
        UUID tooOld2 = publish(topic);
        UUID tooOld3 = publish(topic);
        UUID recent = publish(topic);
        UUID justPublished = publish(topic);
        UUID stillPending = publish(topic);
        setPublishedAgo(tooOld1, "8 days");
        setPublishedAgo(tooOld2, "10 days");
        setPublishedAgo(tooOld3, "7 days 1 minute");
        setPublishedAgo(recent, "6 days");
        setPublishedAgo(justPublished, "10 minutes");
        jdbc.sql("UPDATE outbox_event SET created_at = now() - interval '30 days' WHERE id = :id")
                .param("id", stillPending)
                .update();
        OutboxCleanup cleanup =
                new OutboxCleanup(repository, outboxTransactionTemplate, metrics, Duration.ofDays(7), 2);

        assertThat(cleanup.cleanUp()).isEqualTo(3);

        assertThat(count("id IN (SELECT id FROM outbox_event)")).isEqualTo(3);
        assertThat(jdbc.sql("SELECT id FROM outbox_event").query(UUID.class).list())
                .containsExactlyInAnyOrder(recent, justPublished, stillPending);
        assertThat(cleanup.cleanUp()).isZero();
        assertThat(meters.get("outbox.cleanup.deleted").counter().count()).isEqualTo(3.0);
    }

    @Test
    void gaugesReportBacklogAndOldestAge() {
        String topic = "metrics-it";
        for (int i = 0; i < 3; i++) {
            publish(topic);
        }
        jdbc.sql("UPDATE outbox_event SET created_at = now() - interval '2 minutes'")
                .update();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(meters.get("outbox.pending").gauge().value()).isEqualTo(3.0);
            assertThat(meters.get("outbox.oldest.age.seconds").gauge().value()).isBetween(110.0, 180.0);
        });

        jdbc.sql("UPDATE outbox_event SET published_at = now()").update();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(meters.get("outbox.pending").gauge().value()).isZero();
            assertThat(meters.get("outbox.oldest.age.seconds").gauge().value()).isZero();
        });
    }

    @Test
    void createdAtIsAssignedPerStatementSoInsertionOrderIsPreserved() {
        UUID orderId = UUID.randomUUID();
        transaction().executeWithoutResult(status -> {
            for (int sequence = 1; sequence <= 20; sequence++) {
                publisher.publish(Events.orderCreated(orderId, sequence), "order-it");
            }
        });

        var sequences = repository.claimBatch(100).stream()
                .map(m -> m.payload())
                .map(json -> json.replaceAll(".*\"itemCount\": ?(\\d+).*", "$1"))
                .map(Integer::parseInt)
                .toList();

        assertThat(sequences)
                .isEqualTo(java.util.stream.IntStream.rangeClosed(1, 20).boxed().toList());
        assertThat(jdbc.sql("SELECT count(DISTINCT created_at) FROM outbox_event")
                        .query(Integer.class)
                        .single())
                .isEqualTo(20);
    }

    private UUID publish(String topic) {
        var envelope = Events.orderCreated(UUID.randomUUID(), 1);
        transaction().executeWithoutResult(status -> publisher.publish(envelope, topic));
        return envelope.eventId();
    }

    private void setPublishedAgo(UUID id, String interval) {
        jdbc.sql("UPDATE outbox_event SET published_at = now() - CAST(:age AS interval) WHERE id = :id")
                .param("age", interval)
                .param("id", id)
                .update();
    }
}
