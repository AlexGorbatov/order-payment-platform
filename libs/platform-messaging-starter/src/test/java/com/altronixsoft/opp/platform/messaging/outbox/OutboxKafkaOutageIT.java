package com.altronixsoft.opp.platform.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.altronixsoft.opp.contracts.EventEnvelope;
import com.altronixsoft.opp.contracts.OrderCreated;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** F01: Kafka is down while orders are created: rows accumulate, then drain in per-key order. */
class OutboxKafkaOutageIT extends AbstractOutboxIT {

    private static final int KEYS = 3;
    private static final int EVENTS_PER_KEY = 10;

    @Autowired
    SimpleMeterRegistry meters;

    @Test
    void rowsAccumulateWhileKafkaIsPausedAndDrainInOrderAfterwards() {
        String topic = KafkaTestSupport.newTopic();
        List<UUID> keys = java.util.stream.IntStream.range(0, KEYS)
                .mapToObj(i -> UUID.randomUUID())
                .toList();

        // Warm-up: the producer connects and learns the topic metadata while Kafka is healthy.
        publish(topic, UUID.randomUUID(), 1);
        await().atMost(Duration.ofSeconds(30)).until(() -> count("published_at IS NULL") == 0);

        pauseKafka();
        try {
            for (int sequence = 1; sequence <= EVENTS_PER_KEY; sequence++) {
                for (UUID key : keys) {
                    publish(topic, key, sequence);
                }
            }

            int expectedPending = KEYS * EVENTS_PER_KEY;
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                assertThat(count("published_at IS NULL")).isEqualTo(expectedPending);
                // "attempts grows": the head row is retried every cycle; the batch stops there, the rest stay at 0.
                assertThat(maxAttempts()).isGreaterThanOrEqualTo(2);
                assertThat(count("attempts > 0")).isEqualTo(1);
                assertThat(jdbc.sql("SELECT last_error FROM outbox_event WHERE attempts > 0")
                                .query(String.class)
                                .single())
                        .isNotBlank();
                assertThat(meters.get("outbox.pending").gauge().value()).isEqualTo(expectedPending);
                assertThat(meters.get("outbox.oldest.age.seconds").gauge().value())
                        .isGreaterThan(0.5);
                assertThat(meters.get("outbox.publish.failure")
                                .tag("topic", topic)
                                .counter()
                                .count())
                        .isGreaterThanOrEqualTo(2.0);
            });
        } finally {
            unpauseKafka();
        }

        await().atMost(Duration.ofSeconds(90)).until(() -> count("published_at IS NULL") == 0);

        List<ConsumerRecord<String, String>> records =
                KafkaTestSupport.read(topic, KEYS * EVENTS_PER_KEY + 1, Duration.ofSeconds(30));
        assertThat(firstOccurrences(records)).as("every event reached Kafka").hasSize(KEYS * EVENTS_PER_KEY + 1);
        Map<String, List<Integer>> sequencesPerKey = sequencesPerKey(records);
        for (UUID key : keys) {
            assertThat(sequencesPerKey.get(key.toString()))
                    .as("order of events of key %s", key)
                    .isEqualTo(java.util.stream.IntStream.rangeClosed(1, EVENTS_PER_KEY)
                            .boxed()
                            .toList());
        }
        assertThat(meters.get("outbox.pending").gauge().value()).isZero();
        assertThat(meters.get("outbox.oldest.age.seconds").gauge().value()).isZero();
    }

    private void publish(String topic, UUID key, int sequence) {
        // The per-key sequence travels in itemCount.
        EventEnvelope<OrderCreated> envelope = Events.orderCreated(key, sequence);
        transaction().executeWithoutResult(status -> publisher.publish(envelope, topic));
    }

    private int maxAttempts() {
        return jdbc.sql("SELECT COALESCE(max(attempts), 0) FROM outbox_event")
                .query(Integer.class)
                .single();
    }

    /** Event ids in order of first appearance; duplicates after the outage are legal (at-least-once, F02). */
    private static Set<UUID> firstOccurrences(List<ConsumerRecord<String, String>> records) {
        Set<UUID> ids = new HashSet<>();
        records.forEach(r -> ids.add(KafkaTestSupport.parse(r).eventId()));
        return ids;
    }

    private static Map<String, List<Integer>> sequencesPerKey(List<ConsumerRecord<String, String>> records) {
        Map<String, List<Integer>> result = new LinkedHashMap<>();
        Set<UUID> seen = new HashSet<>();
        for (ConsumerRecord<String, String> record : records) {
            EventEnvelope<?> envelope = KafkaTestSupport.parse(record);
            if (seen.add(envelope.eventId())) {
                int sequence = ((OrderCreated) envelope.payload()).itemCount();
                result.computeIfAbsent(record.key(), k -> new ArrayList<>()).add(sequence);
            }
        }
        return result;
    }

    private static void pauseKafka() {
        TestInfrastructure.KAFKA
                .getDockerClient()
                .pauseContainerCmd(TestInfrastructure.KAFKA.getContainerId())
                .exec();
    }

    private static void unpauseKafka() {
        TestInfrastructure.KAFKA
                .getDockerClient()
                .unpauseContainerCmd(TestInfrastructure.KAFKA.getContainerId())
                .exec();
    }
}
