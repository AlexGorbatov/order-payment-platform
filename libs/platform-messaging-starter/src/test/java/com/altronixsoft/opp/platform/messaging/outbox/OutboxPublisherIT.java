package com.altronixsoft.opp.platform.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.altronixsoft.opp.contracts.EventEnvelope;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.test.simple.SimpleTracer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

class OutboxPublisherIT extends AbstractOutboxIT {

    @Autowired
    SimpleTracer tracer;

    @Autowired
    MeterRegistry meters;

    @Test
    @SuppressWarnings("try") // SpanInScope is only needed for its side effect (current span)
    void publishInTransactionDeliversToKafkaWithKeyValueAndHeaders() {
        String topic = KafkaTestSupport.newTopic();
        UUID orderId = UUID.randomUUID();
        EventEnvelope<?> envelope = Events.orderCreated(orderId, 1);

        Span span = tracer.nextSpan().name("create-order").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            createOrderAndPublish(envelope, topic);
        } finally {
            span.end();
        }

        List<ConsumerRecord<String, String>> records = KafkaTestSupport.read(topic, 1, Duration.ofSeconds(20));
        assertThat(records).hasSize(1);
        ConsumerRecord<String, String> record = records.getFirst();
        assertThat(record.key()).isEqualTo(orderId.toString());
        assertThat(KafkaTestSupport.parse(record)).isEqualTo(envelope);
        Map<String, String> headers = KafkaTestSupport.headers(record);
        assertThat(headers)
                .containsEntry("eventType", "OrderCreated")
                .containsEntry("eventVersion", "1")
                .containsEntry("correlationId", envelope.correlationId().toString());
        assertThat(headers.get("traceparent")).isEqualTo(expectedTraceparent(span));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(count("published_at IS NOT NULL")).isEqualTo(1);
            assertThat(jdbc.sql("SELECT attempts FROM outbox_event")
                            .query(Integer.class)
                            .single())
                    .isZero();
        });
        assertThat(meters.get("outbox.publish.success")
                        .tag("topic", topic)
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(meters.get("outbox.publish.latency")
                        .tag("topic", topic)
                        .timer()
                        .count())
                .isEqualTo(1L);
    }

    @Test
    void rowCarriesTheEnvelopeAndRoutingColumns() {
        String topic = KafkaTestSupport.newTopic();
        UUID orderId = UUID.randomUUID();
        EventEnvelope<?> envelope = Events.orderCreated(orderId, 1);

        createOrderAndPublish(envelope, topic);

        Map<String, Object> row = jdbc.sql("""
                        SELECT id, aggregate_type, aggregate_id, partition_key, topic, event_type, event_version,
                               payload->>'eventId' AS payload_event_id, payload->'payload'->>'orderId' AS payload_order_id,
                               headers->>'eventType' AS header_event_type
                        FROM outbox_event
                        """).query().singleRow();
        assertThat(row)
                .containsEntry("id", envelope.eventId())
                .containsEntry("aggregate_type", "Order")
                .containsEntry("aggregate_id", orderId)
                .containsEntry("partition_key", orderId.toString())
                .containsEntry("topic", topic)
                .containsEntry("event_type", "OrderCreated")
                .containsEntry("event_version", 1)
                .containsEntry("payload_event_id", envelope.eventId().toString())
                .containsEntry("payload_order_id", orderId.toString())
                .containsEntry("header_event_type", "OrderCreated");
    }

    @Test
    void publishWithoutActiveSpanOmitsTraceparent() {
        String topic = KafkaTestSupport.newTopic();

        createOrderAndPublish(Events.orderCreated(UUID.randomUUID(), 1), topic);

        ConsumerRecord<String, String> record =
                KafkaTestSupport.read(topic, 1, Duration.ofSeconds(20)).getFirst();
        assertThat(KafkaTestSupport.headers(record)).doesNotContainKey("traceparent");
    }

    @Test
    void rollbackLeavesNeitherRowNorKafkaEventNorBusinessChange() {
        String topic = KafkaTestSupport.newTopic();
        EventEnvelope<?> envelope = Events.orderCreated(UUID.randomUUID(), 1);

        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> {
                    jdbc.sql("INSERT INTO demo_order (id) VALUES (:id)")
                            .param("id", UUID.fromString(envelope.partitionKey()))
                            .update();
                    publisher.publish(envelope, topic);
                    throw new IllegalStateException("business failure after publish");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("business failure");

        assertThat(count("true")).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM demo_order")
                        .query(Integer.class)
                        .single())
                .isZero();
        // The relay polls every 100 ms; give it several cycles to prove that nothing is ever sent.
        assertThat(KafkaTestSupport.readAllAfter(topic, Duration.ofSeconds(2))).isEmpty();
    }

    @Test
    void publishOutsideTransactionFails() {
        String topic = KafkaTestSupport.newTopic();

        assertThatThrownBy(() -> publisher.publish(Events.orderCreated(UUID.randomUUID(), 1), topic))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(count("true")).isZero();
    }

    @Test
    void publishingTheSameEventTwiceFailsTheTransaction() {
        String topic = KafkaTestSupport.newTopic();
        EventEnvelope<?> envelope = Events.orderCreated(UUID.randomUUID(), 1);
        createOrderAndPublish(envelope, topic);

        assertThatThrownBy(() -> transaction().executeWithoutResult(status -> publisher.publish(envelope, topic)))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    void blankTopicIsRejected() {
        assertThatThrownBy(() -> transaction()
                        .executeWithoutResult(
                                status -> publisher.publish(Events.orderCreated(UUID.randomUUID(), 1), " ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String expectedTraceparent(Span span) {
        TraceContext context = span.context();
        String traceId = "0".repeat(32 - context.traceId().length()) + context.traceId();
        String spanId = "0".repeat(16 - context.spanId().length()) + context.spanId();
        return "00-" + traceId + "-" + spanId + "-" + (Boolean.TRUE.equals(context.sampled()) ? "01" : "00");
    }
}
