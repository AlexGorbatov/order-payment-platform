package com.altronixsoft.opp.platform.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.altronixsoft.opp.contracts.EventEnvelope;
import com.altronixsoft.opp.platform.messaging.outbox.KafkaTestSupport;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConsumerRetryAndDltIT extends AbstractMessagingIT {

    /** F15: a poison message goes to the DLT at once, without blocking the partition or touching retry topics. */
    @Test
    void brokenJsonGoesStraightToTheDeadLetterTableWithoutRetries() {
        String broken = "{\"eventId\": not json";
        KafkaTestSupport.send(TOPIC, "poison-key", broken, Map.of());

        await().atMost(seconds(30)).until(() -> count("dead_letter_message", "true") == 1);

        Map<String, Object> row = jdbc.sql("""
                        SELECT original_topic, dlt_topic, message_key, status, exception_class, exception_message,
                               original_partition, original_offset, headers->>'kafka_original-topic' AS source
                        FROM dead_letter_message
                        """).query().singleRow();
        assertThat(row)
                .containsEntry("original_topic", TOPIC)
                .containsEntry("dlt_topic", TOPIC + "-dlt")
                .containsEntry("message_key", "poison-key")
                .containsEntry("status", "NEW");
        assertThat((String) row.get("exception_class")).endsWith("EventSerdeException");
        assertThat((String) row.get("exception_message")).contains("Malformed event JSON");
        assertThat(row.get("original_partition")).isNotNull();
        assertThat(row.get("original_offset")).isNotNull();
        assertThat(row.get("source")).isEqualTo(TOPIC);
        assertThat(jdbc.sql("SELECT convert_from(payload, 'UTF8') FROM dead_letter_message")
                        .query(String.class)
                        .single())
                .isEqualTo(broken);
        assertThat(retryTopicsRecordCount()).as("records on retry topics").isZero();
        assertThat(meters.get("dlt.messages").tag("topic", TOPIC).counter().count())
                .isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void aPoisonMessageDoesNotBlockTheEventsBehindIt() {
        EventEnvelope<?> next = event();
        KafkaTestSupport.send(TOPIC, next.partitionKey(), "garbage", Map.of());
        send(next);

        await().atMost(seconds(30))
                .untilAsserted(() -> assertThat(handler.effects(next.eventId())).isEqualTo(1));
        assertThat(count("dead_letter_message", "true")).isEqualTo(1);
    }

    @Test
    void transientFailureTwiceSucceedsOnTheThirdBlockingAttemptAndLeavesNoDeadLetter() {
        EventEnvelope<?> envelope = event();
        handler.failTimes(envelope.eventId(), 2);

        send(envelope);

        await().atMost(seconds(30))
                .untilAsserted(
                        () -> assertThat(handler.effects(envelope.eventId())).isEqualTo(1));
        assertThat(handler.invocations(envelope.eventId())).isEqualTo(3);
        sleep(Duration.ofSeconds(2));
        assertThat(count("dead_letter_message", "true")).isZero();
        assertThat(retryTopicsRecordCount()).isZero();
        assertThat(count("inbox_message", "event_id = '" + envelope.eventId() + "'"))
                .isEqualTo(1);
    }

    @Test
    void permanentFailureTravelsThroughTheRetryTopicsToTheDeadLetterTopic() {
        EventEnvelope<?> envelope = event();
        handler.failEverything(true);

        send(envelope);

        await().atMost(seconds(60)).until(() -> count("dead_letter_message", "true") == 1);

        // one record on every retry topic: the message really took the non-blocking path
        for (int i = 0; i < 3; i++) {
            assertThat(KafkaTestSupport.read(TOPIC + "-retry-" + i, 1, seconds(5)))
                    .as("retry topic %d", i)
                    .hasSizeGreaterThanOrEqualTo(1);
        }
        assertThat(handler.invocations(envelope.eventId())).isGreaterThanOrEqualTo(4);
        Map<String, Object> row = jdbc.sql(
                        "SELECT original_topic, dlt_topic, message_key, status, exception_message FROM dead_letter_message")
                .query()
                .singleRow();
        assertThat(row)
                .containsEntry("original_topic", TOPIC)
                .containsEntry("dlt_topic", TOPIC + "-dlt")
                .containsEntry("message_key", envelope.partitionKey())
                .containsEntry("status", "NEW");
        assertThat((String) row.get("exception_message")).contains("boom");
        // The failed attempts rolled back together with their inbox rows, so a replay will not be taken for a
        // duplicate.
        assertThat(count("inbox_message", "event_id = '" + envelope.eventId() + "'"))
                .isZero();
        assertThat(handler.effects(envelope.eventId())).isZero();
        // the payload is exactly what was sent
        assertThat(jdbc.sql("SELECT convert_from(payload, 'UTF8') FROM dead_letter_message")
                        .query(String.class)
                        .single())
                .isEqualTo(SERDE.toJson(envelope));
    }

    @Test
    void aNonRetryableBusinessErrorSkipsRetriesAndBlockingAttempts() {
        EventEnvelope<?> envelope = event();
        handler.failNonRetryable(true);

        send(envelope);

        await().atMost(seconds(30)).until(() -> count("dead_letter_message", "true") == 1);
        assertThat(handler.invocations(envelope.eventId())).isEqualTo(1);
        assertThat(retryTopicsRecordCount()).isZero();
        assertThat(jdbc.sql("SELECT exception_class FROM dead_letter_message")
                        .query(String.class)
                        .single())
                .endsWith("NonRetryableEventException");
    }

    @Test
    void aPayloadWithNulCharactersIsStoredIntact() {
        String poison = "\u0000{\"x\":\u0000}";
        KafkaTestSupport.send(TOPIC, "nul\u0000key", poison, Map.of("weird\u0000", "va\u0000lue"));

        await().atMost(seconds(30)).until(() -> count("dead_letter_message", "true") == 1);

        assertThat(jdbc.sql("SELECT payload FROM dead_letter_message")
                        .query(byte[].class)
                        .single())
                .isEqualTo(poison.getBytes(StandardCharsets.UTF_8));
        // PostgreSQL cannot store NUL in text/jsonb: it is replaced, never rejected
        assertThat(jdbc.sql("SELECT message_key FROM dead_letter_message")
                        .query(String.class)
                        .single())
                .isEqualTo("nul�key");
    }

    private int retryTopicsRecordCount() {
        int total = 0;
        for (int i = 0; i < 3; i++) {
            total += KafkaTestSupport.readAllAfter(TOPIC + "-retry-" + i, Duration.ofMillis(300))
                    .size();
        }
        return total;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
