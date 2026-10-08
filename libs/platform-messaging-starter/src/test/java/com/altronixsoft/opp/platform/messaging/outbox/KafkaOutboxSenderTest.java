package com.altronixsoft.opp.platform.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

class KafkaOutboxSenderTest {

    @Test
    void producerIsIdempotentAndWaitsForAllReplicas() {
        Map<String, Object> props =
                KafkaOutboxSender.producerProperties(Map.of("bootstrap.servers", "kafka:9092"), Duration.ofSeconds(10));

        assertThat(props)
                .containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "kafka:9092")
                .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
                .containsEntry(ProducerConfig.LINGER_MS_CONFIG, 0);
    }

    @Test
    void serviceProducerSettingsCannotWeakenTheGuarantees() {
        Map<String, Object> props = KafkaOutboxSender.producerProperties(
                Map.of(
                        ProducerConfig.ACKS_CONFIG, "0",
                        ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false,
                        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.example.JsonSerializer"),
                Duration.ofSeconds(10));

        assertThat(props)
                .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
                .containsEntry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class)
                .containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    }

    @Test
    void deliveryTimeoutIsBoundToTheAckTimeoutAndStaysConsistentForTheKafkaClient() {
        Map<String, Object> props = KafkaOutboxSender.producerProperties(Map.of(), Duration.ofSeconds(2));

        int delivery = (int) props.get(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG);
        int request = (int) props.get(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG);
        int linger = (int) props.get(ProducerConfig.LINGER_MS_CONFIG);
        assertThat(delivery).isEqualTo(2_000);
        assertThat(props).containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2_000);
        // The Kafka client rejects delivery.timeout.ms < linger.ms + request.timeout.ms.
        assertThat(delivery).isGreaterThanOrEqualTo(linger + request);
    }
}
