package com.altronixsoft.opp.e2e.support;

import com.altronixsoft.opp.contracts.DomainEvent;
import com.altronixsoft.opp.contracts.EventEnvelope;
import com.altronixsoft.opp.contracts.EventSerde;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Puts records on the saga topics from outside, the way a faulty producer (or an operator with a console tool) could:
 * well-formed events that the state of the receiving service cannot explain, and plain garbage.
 */
public final class Kafka {

    private static final EventSerde SERDE = new EventSerde();

    private final Platform platform;

    public Kafka(Platform platform) {
        this.platform = platform;
    }

    /** An event, wrapped in an envelope as a service would, on {@code topic} with the order id as key. */
    public <T extends DomainEvent> EventEnvelope<T> publish(String topic, T event) {
        EventEnvelope<T> envelope = EventEnvelope.create(event, UUID.randomUUID(), null, Clock.systemUTC());
        send(topic, envelope.partitionKey(), SERDE.toJson(envelope), Map.of("eventType", envelope.eventType()));
        return envelope;
    }

    /** Any value under any key: bytes the consumer cannot make sense of. */
    public void send(String topic, String key, String value, Map<String, String> headers) {
        Properties config = new Properties();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, platform.kafkaBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(config)) {
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
            headers.forEach((name, header) -> record.headers().add(name, header.getBytes(StandardCharsets.UTF_8)));
            producer.send(record).get(30, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Cannot send to " + topic, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
