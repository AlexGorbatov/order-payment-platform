package com.altronixsoft.opp.platform.messaging.outbox;

import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** PostgreSQL and Kafka shared by all integration tests of the JVM (started once, stopped by Testcontainers' reaper). */
final class TestInfrastructure {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    static {
        Startables.deepStart(POSTGRES, KAFKA).join();
    }

    private TestInfrastructure() {}
}
