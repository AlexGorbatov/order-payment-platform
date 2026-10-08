package com.altronixsoft.opp.platform.messaging.consumer;

import com.altronixsoft.opp.contracts.EventEnvelope;
import com.altronixsoft.opp.contracts.EventSerde;
import com.altronixsoft.opp.platform.messaging.outbox.Events;
import com.altronixsoft.opp.platform.messaging.outbox.KafkaTestSupport;
import com.altronixsoft.opp.platform.messaging.outbox.TestInfrastructure;
import com.altronixsoft.opp.platform.messaging.outbox.testapp.OutboxTestApplication;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A consuming service with the whole platform wired in: inbox, retry topics, dead-letter persistence and admin API, plus
 * the outbox relay that replays go through. Delays are short so that a message travels main topic → retry topics → DLT
 * in a few seconds.
 */
@SpringBootTest(
        classes = OutboxTestApplication.class,
        properties = {
            "spring.application.name=messaging-it",
            "spring.flyway.locations=classpath:db/migration/test",
            "spring.task.scheduling.pool.size=4",
            "spring.kafka.consumer.group-id=" + TestEventListener.GROUP,
            "test.topic=messaging-it.events.v1",
            "platform.outbox.relay.initial-delay=0s",
            "platform.outbox.relay.fixed-delay=100ms",
            "platform.outbox.relay.ack-timeout=3s",
            "platform.outbox.cleanup.enabled=false",
            "platform.inbox.cleanup.enabled=false",
            "platform.consumer.retry.blocking-retries=2",
            "platform.consumer.retry.blocking-interval=100ms",
            "platform.consumer.retry.topic-delays=300ms,600ms,1s",
            "platform.consumer.retry.topic-partitions=1",
            "platform.dead-letters.persister.metadata-refresh=1s"
        })
@AutoConfigureMockMvc
@Import(MessagingTestConfig.class)
abstract class AbstractMessagingIT {

    protected static final String TOPIC = "messaging-it.events.v1";
    protected static final EventSerde SERDE = new EventSerde();

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected TestHandler handler;

    @Autowired
    protected SimpleMeterRegistry meters;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> TestInfrastructure.databaseUrl("messaging"));
        registry.add("spring.datasource.username", TestInfrastructure.POSTGRES::getUsername);
        registry.add("spring.datasource.password", TestInfrastructure.POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", TestInfrastructure.KAFKA::getBootstrapServers);
    }

    @BeforeEach
    void resetState() {
        handler.reset();
        jdbc.sql("TRUNCATE demo_effect, inbox_message, dead_letter_message, outbox_event")
                .update();
    }

    protected static EventEnvelope<?> event() {
        return Events.orderCreated(UUID.randomUUID(), 1);
    }

    protected static void send(EventEnvelope<?> envelope) {
        KafkaTestSupport.send(TOPIC, envelope.partitionKey(), SERDE.toJson(envelope), Map.of());
    }

    protected int count(String table, String where) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + where)
                .query(Integer.class)
                .single();
    }

    protected static Duration seconds(int seconds) {
        return Duration.ofSeconds(seconds);
    }
}
