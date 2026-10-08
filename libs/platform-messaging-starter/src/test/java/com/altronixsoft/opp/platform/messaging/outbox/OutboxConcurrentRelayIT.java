package com.altronixsoft.opp.platform.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.altronixsoft.opp.contracts.EventEnvelope;
import com.altronixsoft.opp.contracts.OrderCreated;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

/** Several relay instances sharing one outbox: no row is handled twice and per-key order survives. */
class OutboxConcurrentRelayIT extends AbstractRelayDisabledIT {

    private static final int KEYS = 50;
    private static final int PER_KEY = 4;
    private static final int TOTAL = KEYS * PER_KEY;

    @Test
    void twoRelaysHoldingTransactionsAtTheSameTimeClaimDisjointRows() throws Exception {
        String topic = KafkaTestSupport.newTopic();
        List<UUID> ids = publishKeyByKey(topic);
        OutboxSender kafka = kafkaSender();
        GatedSender a = new GatedSender(kafka, true);
        GatedSender b = new GatedSender(kafka, false);

        // A claims the first 100 rows and sits in its transaction (first send is held) …
        CompletableFuture<Integer> resultA =
                CompletableFuture.supplyAsync(() -> relay(a, 100).pollOnce());
        assertThat(a.firstSendReached.await(20, TimeUnit.SECONDS)).isTrue();
        // … while B runs a full cycle: it must skip A's locked rows and take the next 100.
        int publishedByB = relay(b, 100).pollOnce();
        a.release();
        int publishedByA = resultA.get(30, TimeUnit.SECONDS);

        assertThat(publishedByB).isEqualTo(100);
        assertThat(publishedByA).isEqualTo(100);
        assertThat(a.sent).doesNotContainAnyElementsOf(b.sent);
        assertThat(a.sent).containsExactlyElementsOf(ids.subList(0, 100));
        assertThat(b.sent).containsExactlyElementsOf(ids.subList(100, TOTAL));
        assertThat(count("published_at IS NOT NULL")).isEqualTo(TOTAL);

        assertDeliveredOnceInKeyOrder(topic);
        ((KafkaOutboxSender) kafka).destroy();
    }

    @Test
    void aRelayDoesNotOvertakeAnOlderRowOfTheSameKeyHeldByAnotherInstance() throws Exception {
        String topic = KafkaTestSupport.newTopic();
        publishInterleaved(topic);
        OutboxSender kafka = kafkaSender();
        GatedSender a = new GatedSender(kafka, true);
        GatedSender b = new GatedSender(kafka, false);

        // A holds rounds 1-2 of every key; B claims rounds 3-4, each behind an older row that A has not committed.
        CompletableFuture<Integer> resultA =
                CompletableFuture.supplyAsync(() -> relay(a, 100).pollOnce());
        assertThat(a.firstSendReached.await(20, TimeUnit.SECONDS)).isTrue();
        int publishedByB = relay(b, 100).pollOnce();
        a.release();
        int publishedByA = resultA.get(30, TimeUnit.SECONDS);

        assertThat(publishedByB)
                .as("B must not send rows that would overtake A's")
                .isZero();
        assertThat(b.sent).isEmpty();
        assertThat(publishedByA).isEqualTo(100);

        // The next cycle finds nothing in the way.
        assertThat(relay(b, 100).pollOnce()).isEqualTo(100);

        assertDeliveredOnceInKeyOrder(topic);
        ((KafkaOutboxSender) kafka).destroy();
    }

    @Test
    void concurrentCyclesInLoopNeverDuplicateOrReorder() throws Exception {
        String topic = KafkaTestSupport.newTopic();
        publishInterleaved(topic);
        KafkaOutboxSender kafka = kafkaSender();
        OutboxRelay first = relay(kafka, 30);
        OutboxRelay second = relay(kafka, 30);

        CompletableFuture<Void> loopA = CompletableFuture.runAsync(() -> drain(first));
        CompletableFuture<Void> loopB = CompletableFuture.runAsync(() -> drain(second));
        CompletableFuture.allOf(loopA, loopB).get(60, TimeUnit.SECONDS);

        assertThat(count("published_at IS NULL")).isZero();
        assertDeliveredOnceInKeyOrder(topic);
        kafka.destroy();
    }

    @Test
    void claimsAreExclusiveAcrossTransactions() throws Exception {
        String topic = KafkaTestSupport.newTopic();
        publishKeyByKey(topic);
        CompletableFuture<List<UUID>> holder = new CompletableFuture<>();
        CompletableFuture<Void> release = new CompletableFuture<>();

        CompletableFuture<Void> first =
                CompletableFuture.runAsync(() -> transaction().executeWithoutResult(status -> {
                    holder.complete(repository.claimBatch(10).stream()
                            .map(OutboxMessage::id)
                            .toList());
                    release.join();
                }));
        List<UUID> claimedByFirst = holder.get(20, TimeUnit.SECONDS);

        List<UUID> claimedBySecond = transaction()
                .execute(status -> repository.claimBatch(10).stream()
                        .map(OutboxMessage::id)
                        .toList());
        release.complete(null);
        first.get(20, TimeUnit.SECONDS);

        assertThat(claimedByFirst).hasSize(10);
        assertThat(claimedBySecond).hasSize(10).doesNotContainAnyElementsOf(claimedByFirst);
    }

    private void drain(OutboxRelay relay) {
        await().atMost(Duration.ofSeconds(50))
                .pollInterval(Duration.ofMillis(10))
                .until(() -> {
                    relay.pollOnce();
                    return count("published_at IS NULL") == 0;
                });
    }

    /** key0×4, key1×4, … so the first 100 rows hold 25 whole keys. */
    private List<UUID> publishKeyByKey(String topic) {
        List<UUID> ids = new ArrayList<>();
        for (int key = 0; key < KEYS; key++) {
            UUID orderId = UUID.randomUUID();
            List<EventEnvelope<OrderCreated>> events = new ArrayList<>();
            for (int sequence = 1; sequence <= PER_KEY; sequence++) {
                events.add(Events.orderCreated(orderId, sequence));
            }
            transaction().executeWithoutResult(status -> events.forEach(e -> publisher.publish(e, topic)));
            events.forEach(e -> ids.add(e.eventId()));
        }
        return ids;
    }

    /** round 1 of every key, then round 2, … so every key has rows in every region of the table. */
    private void publishInterleaved(String topic) {
        List<UUID> keys = new ArrayList<>();
        for (int i = 0; i < KEYS; i++) {
            keys.add(UUID.randomUUID());
        }
        for (int sequence = 1; sequence <= PER_KEY; sequence++) {
            for (UUID key : keys) {
                EventEnvelope<OrderCreated> event = Events.orderCreated(key, sequence);
                transaction().executeWithoutResult(status -> publisher.publish(event, topic));
            }
        }
    }

    private void assertDeliveredOnceInKeyOrder(String topic) {
        List<ConsumerRecord<String, String>> records = KafkaTestSupport.read(topic, TOTAL, Duration.ofSeconds(30));
        Set<UUID> eventIds = new HashSet<>();
        Map<String, List<Integer>> sequences = new LinkedHashMap<>();
        for (ConsumerRecord<String, String> record : records) {
            EventEnvelope<?> envelope = KafkaTestSupport.parse(record);
            assertThat(eventIds.add(envelope.eventId()))
                    .as("event %s delivered twice", envelope.eventId())
                    .isTrue();
            sequences
                    .computeIfAbsent(record.key(), k -> new ArrayList<>())
                    .add(((OrderCreated) envelope.payload()).itemCount());
        }
        assertThat(records).hasSize(TOTAL);
        assertThat(sequences).hasSize(KEYS);
        sequences.forEach(
                (key, seen) -> assertThat(seen).as("order of key %s", key).isEqualTo(List.of(1, 2, 3, 4)));
    }
}
