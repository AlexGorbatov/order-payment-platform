package com.altronixsoft.opp.platform.messaging.outbox;

import com.altronixsoft.opp.contracts.EventEnvelope;
import com.altronixsoft.opp.platform.messaging.outbox.testapp.OutboxTestApplication;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Spring context of the test application against real PostgreSQL and Kafka. */
@SpringBootTest(
        classes = OutboxTestApplication.class,
        properties = {
            // The starter registers classpath:db/migration/platform with Flyway itself.
            "spring.flyway.locations=classpath:db/migration/test",
            "spring.task.scheduling.pool.size=4",
            "platform.outbox.relay.initial-delay=0s",
            "platform.outbox.relay.fixed-delay=100ms",
            "platform.outbox.relay.ack-timeout=2s",
            "platform.outbox.cleanup.enabled=false",
            "platform.outbox.metrics.cache-ttl=200ms"
        })
abstract class AbstractOutboxIT {

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected PlatformTransactionManager transactionManager;

    @Autowired
    protected OutboxPublisher publisher;

    @Autowired
    protected OutboxRepository repository;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestInfrastructure.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", TestInfrastructure.POSTGRES::getUsername);
        registry.add("spring.datasource.password", TestInfrastructure.POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", TestInfrastructure.KAFKA::getBootstrapServers);
    }

    @BeforeEach
    void cleanTables() {
        jdbc.sql("TRUNCATE outbox_event, demo_order").update();
    }

    protected TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    /** Business change + event in one transaction, like a service use case. */
    protected void createOrderAndPublish(EventEnvelope<?> envelope, String topic) {
        transaction().executeWithoutResult(status -> {
            jdbc.sql("INSERT INTO demo_order (id) VALUES (:id)")
                    .param("id", UUID.fromString(envelope.partitionKey()))
                    .update();
            publisher.publish(envelope, topic);
        });
    }

    protected int count(String where) {
        return jdbc.sql("SELECT count(*) FROM outbox_event WHERE " + where)
                .query(Integer.class)
                .single();
    }
}
