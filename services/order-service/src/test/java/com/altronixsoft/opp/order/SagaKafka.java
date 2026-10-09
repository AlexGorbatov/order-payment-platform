package com.altronixsoft.opp.order;

import static org.awaitility.Awaitility.await;

import com.altronixsoft.opp.contracts.EventEnvelope;
import com.altronixsoft.opp.contracts.EventSerde;
import com.altronixsoft.opp.contracts.PaymentEvent;
import com.altronixsoft.opp.contracts.Topics;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/** What payment-service would do on Kafka in the saga tests: publish payment events and read order events. */
final class SagaKafka implements AutoCloseable {

    static final EventSerde SERDE = new EventSerde();

    /** A record of {@code order.events.v1}. */
    record Received(String key, String value, Map<String, String> headers) {

        EventEnvelope<?> envelope() {
            return SERDE.fromJson(value);
        }
    }

    private final KafkaProducer<String, String> producer;
    private final KafkaConsumer<String, String> consumer;
    private final List<Received> orderEvents = new ArrayList<>();

    SagaKafka() {
        producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                TestKafka.bootstrapServers(),
                ProducerConfig.ACKS_CONFIG,
                "all",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class));
        consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                TestKafka.bootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                "saga-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class));
        consumer.subscribe(List.of(Topics.ORDER_EVENTS));
    }

    /** Publishes {@code payment} the way payment-service does: the envelope as value, the order id as key. */
    EventEnvelope<PaymentEvent> publish(PaymentEvent payment, UUID correlationId) {
        EventEnvelope<PaymentEvent> envelope = EventEnvelope.create(payment, correlationId, null, Clock.systemUTC());
        send(envelope);
        return envelope;
    }

    /** Sends exactly this envelope again, as a redelivery would. */
    void send(EventEnvelope<?> envelope) {
        try {
            producer.send(new ProducerRecord<>(Topics.PAYMENT_EVENTS, envelope.partitionKey(), SERDE.toJson(envelope)))
                    .get();
        } catch (ExecutionException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Waits for the order event of {@code orderId} with {@code eventType} on {@code order.events.v1}. */
    Received awaitOrderEvent(UUID orderId, String eventType) {
        return await().atMost(Duration.ofSeconds(30))
                .until(() -> poll(orderId, eventType), Optional::isPresent)
                .orElseThrow();
    }

    /** Everything seen so far for {@code orderId}, in the order of the partition. */
    List<Received> orderEventsOf(UUID orderId) {
        poll(orderId, "");
        return orderEvents.stream()
                .filter(r -> r.key().equals(orderId.toString()))
                .toList();
    }

    private Optional<Received> poll(UUID orderId, String eventType) {
        for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
            orderEvents.add(new Received(record.key(), record.value(), headersOf(record)));
        }
        return orderEvents.stream()
                .filter(r -> r.key().equals(orderId.toString()))
                .filter(r -> eventType.equals(r.headers().get("eventType")))
                .findFirst();
    }

    private static Map<String, String> headersOf(ConsumerRecord<?, ?> record) {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        for (Header header : record.headers()) {
            headers.put(header.key(), new String(header.value(), StandardCharsets.UTF_8));
        }
        return headers;
    }

    @Override
    public void close() {
        producer.close();
        consumer.close();
    }
}
