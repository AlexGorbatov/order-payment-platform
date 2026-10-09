package com.altronixsoft.opp.payment;

import com.altronixsoft.opp.contracts.Topics;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Kafka shared by the integration tests of the JVM, with the two saga topics created like {@code infra/kafka} does
 * (3 partitions each).
 */
final class TestKafka {

    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    static {
        KAFKA.start();
        try (Admin admin =
                Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(
                            new NewTopic(Topics.ORDER_EVENTS, 3, (short) 1),
                            new NewTopic(Topics.PAYMENT_EVENTS, 3, (short) 1)))
                    .all()
                    .get();
        } catch (ExecutionException e) {
            throw new IllegalStateException("Cannot create the saga topics", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private TestKafka() {}

    static String bootstrapServers() {
        return KAFKA.getBootstrapServers();
    }
}
