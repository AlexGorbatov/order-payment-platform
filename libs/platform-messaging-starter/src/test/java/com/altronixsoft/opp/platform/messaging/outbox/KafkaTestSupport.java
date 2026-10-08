package com.altronixsoft.opp.platform.messaging.outbox;

import com.altronixsoft.opp.contracts.EventEnvelope;
import com.altronixsoft.opp.contracts.EventSerde;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

/** Topic creation and reading of everything on a topic, for assertions. */
public final class KafkaTestSupport {

    private static final EventSerde SERDE = new EventSerde();

    private KafkaTestSupport() {}

    public static String newTopic() {
        String topic = "outbox-it-" + UUID.randomUUID();
        try (AdminClient admin = AdminClient.create(
                Map.<String, Object>of("bootstrap.servers", TestInfrastructure.KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 3, (short) 1))).all().get();
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException("Cannot create topic " + topic, e);
        }
        return topic;
    }

    /** Sends a raw record and waits for the broker's acknowledgement. */
    public static void send(String topic, String key, String value, Map<String, String> headers) {
        Properties props = new Properties();
        props.put("bootstrap.servers", TestInfrastructure.KAFKA.getBootstrapServers());
        props.put("key.serializer", org.apache.kafka.common.serialization.StringSerializer.class.getName());
        props.put("value.serializer", org.apache.kafka.common.serialization.StringSerializer.class.getName());
        try (org.apache.kafka.clients.producer.KafkaProducer<String, String> producer =
                new org.apache.kafka.clients.producer.KafkaProducer<>(props)) {
            org.apache.kafka.clients.producer.ProducerRecord<String, String> record =
                    new org.apache.kafka.clients.producer.ProducerRecord<>(topic, key, value);
            headers.forEach((name, header) ->
                    record.headers().add(name, header.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            producer.send(record).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException("Cannot send to " + topic, e);
        }
    }

    /** Reads the topic from the beginning until at least {@code minRecords} arrived or {@code timeout} elapsed. */
    public static List<ConsumerRecord<String, String>> read(String topic, int minRecords, Duration timeout) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, TestInfrastructure.KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(info -> new TopicPartition(topic, info.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            long deadline = System.nanoTime() + timeout.toNanos();
            while (records.size() < minRecords && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
        }
        return records;
    }

    /** Everything currently on the topic after waiting {@code settle}, to prove that nothing (more) arrives. */
    public static List<ConsumerRecord<String, String>> readAllAfter(String topic, Duration settle) {
        sleep(settle);
        return read(topic, 0, Duration.ofSeconds(1));
    }

    public static EventEnvelope<?> parse(ConsumerRecord<String, String> record) {
        return SERDE.fromJson(record.value());
    }

    public static Map<String, String> headers(ConsumerRecord<String, String> record) {
        return java.util.stream.StreamSupport.stream(record.headers().spliterator(), false)
                .collect(Collectors.toMap(
                        h -> h.key(), h -> new String(h.value(), java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
