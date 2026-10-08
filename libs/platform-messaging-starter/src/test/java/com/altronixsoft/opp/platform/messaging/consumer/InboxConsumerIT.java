package com.altronixsoft.opp.platform.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.altronixsoft.opp.contracts.EventEnvelope;
import com.altronixsoft.opp.platform.messaging.inbox.InboxCleanup;
import com.altronixsoft.opp.platform.messaging.inbox.InboxGuard;
import com.altronixsoft.opp.platform.messaging.inbox.InboxRepository;
import com.altronixsoft.opp.platform.messaging.outbox.KafkaTestSupport;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class InboxConsumerIT extends AbstractMessagingIT {

    private static final String GROUP = TestEventListener.GROUP;

    @Autowired
    InboxGuard inbox;

    @Autowired
    InboxRepository inboxRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void duplicateDeliveryRunsTheLogicOnce() {
        EventEnvelope<?> envelope = event();
        double duplicatesBefore = duplicates();

        send(envelope);
        send(envelope);

        await().atMost(seconds(30)).untilAsserted(() -> {
            assertThat(handler.effects(envelope.eventId())).isEqualTo(1);
            assertThat(duplicates()).isEqualTo(duplicatesBefore + 1);
        });
        assertThat(handler.invocations(envelope.eventId())).isEqualTo(1);
        assertThat(count("inbox_message", "event_id = '" + envelope.eventId() + "'"))
                .isEqualTo(1);
        assertThat(count("dead_letter_message", "true")).isZero();
    }

    /** F03: the business transaction committed, then the process "died" before the offset was committed. */
    @Test
    void crashAfterCommitBeforeAckRedeliversWithoutSideEffects() {
        EventEnvelope<?> envelope = event();
        handler.crashAfterCommitOnce(envelope.eventId());
        double duplicatesBefore = duplicates();

        send(envelope);

        await().atMost(seconds(30)).untilAsserted(() -> assertThat(duplicates()).isEqualTo(duplicatesBefore + 1));
        assertThat(handler.effects(envelope.eventId())).isEqualTo(1);
        assertThat(handler.invocations(envelope.eventId())).isEqualTo(1);
        assertThat(count("dead_letter_message", "true")).isZero();
    }

    @Test
    void unknownEventTypeIsSkippedWithoutBlockingOrDeadLettering() {
        EventEnvelope<?> next = event();
        String unknown = """
                {"eventId":"%s","eventType":"ShipmentDispatched","eventVersion":1,"aggregateType":"Shipment",
                 "aggregateId":"%s","partitionKey":"%s","occurredAt":"2026-10-08T12:00:00Z","producer":"x",
                 "correlationId":"%s","payload":{"carrier":"post"}}""".formatted(UUID.randomUUID(), UUID.randomUUID(), next.partitionKey(), UUID.randomUUID());

        KafkaTestSupport.send(TOPIC, next.partitionKey(), unknown, java.util.Map.of());
        send(next);

        await().atMost(seconds(30))
                .untilAsserted(() -> assertThat(handler.effects(next.eventId())).isEqualTo(1));
        assertThat(count("dead_letter_message", "true")).isZero();
        assertThat(count("inbox_message", "true")).isEqualTo(1);
    }

    @Test
    void failedActionRollsBackTheInboxRowSoTheEventCanBeProcessedLater() {
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> inbox.executeOnce(GROUP, eventId, () -> {
                    throw new IllegalStateException("handler failed");
                }))
                .hasMessage("handler failed");
        assertThat(count("inbox_message", "event_id = '" + eventId + "'")).isZero();

        AtomicInteger runs = new AtomicInteger();
        assertThat(inbox.executeOnce(GROUP, eventId, runs::incrementAndGet)).isTrue();
        assertThat(inbox.executeOnce(GROUP, eventId, runs::incrementAndGet)).isFalse();
        assertThat(runs).hasValue(1);
    }

    @Test
    void sameEventIdInAnotherConsumerGroupIsNotADuplicate() {
        UUID eventId = UUID.randomUUID();
        AtomicInteger runs = new AtomicInteger();

        assertThat(inbox.executeOnce("group-a", eventId, runs::incrementAndGet)).isTrue();
        assertThat(inbox.executeOnce("group-b", eventId, runs::incrementAndGet)).isTrue();
        assertThat(inbox.executeOnce("group-a", eventId, runs::incrementAndGet)).isFalse();
        assertThat(runs).hasValue(2);
    }

    @Test
    void concurrentDeliveriesOfTheSameEventRunTheActionOnce() throws Exception {
        UUID eventId = UUID.randomUUID();
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);

        var tasks = java.util.stream.IntStream.range(0, 8)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return inbox.executeOnce(GROUP, eventId, () -> {
                        runs.incrementAndGet();
                        sleep(200);
                    });
                }))
                .toList();
        start.countDown();
        long executed = tasks.stream()
                .map(CompletableFuture::join)
                .filter(Boolean::booleanValue)
                .count();

        assertThat(executed).isEqualTo(1);
        assertThat(runs).hasValue(1);
    }

    @Test
    void cleanupRemovesOnlyRowsOlderThanTheRetention() {
        UUID old = UUID.randomUUID();
        UUID recent = UUID.randomUUID();
        inbox.executeOnce(GROUP, old, () -> {});
        inbox.executeOnce(GROUP, recent, () -> {});
        jdbc.sql("UPDATE inbox_message SET received_at = now() - interval '15 days' WHERE event_id = :id")
                .param("id", old)
                .update();
        InboxCleanup cleanup =
                new InboxCleanup(inboxRepository, new TransactionTemplate(transactionManager), Duration.ofDays(14), 1);

        assertThat(cleanup.cleanUp()).isEqualTo(1);

        assertThat(jdbc.sql("SELECT event_id FROM inbox_message")
                        .query(UUID.class)
                        .list())
                .containsExactly(recent);
    }

    private double duplicates() {
        var counter =
                meters.find("inbox.duplicates").tag("consumerGroup", GROUP).counter();
        return counter == null ? 0 : counter.count();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
