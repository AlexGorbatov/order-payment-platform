package com.altronixsoft.opp.platform.messaging.outbox;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/** Context without the background relay, so tests drive {@link OutboxRelay} instances by hand. */
@TestPropertySource(properties = "platform.outbox.relay.enabled=false")
abstract class AbstractRelayDisabledIT extends AbstractOutboxIT {

    @DynamicPropertySource
    static void ownDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> TestInfrastructure.databaseUrl("outbox_relay_off"));
    }

    @Autowired
    protected KafkaProperties kafkaProperties;

    @Autowired
    protected OutboxMetrics metrics;

    @Autowired
    protected TransactionTemplate outboxTransactionTemplate;

    protected KafkaOutboxSender kafkaSender() {
        return new KafkaOutboxSender(kafkaProperties.buildProducerProperties(), Duration.ofSeconds(5));
    }

    protected OutboxRelay relay(OutboxSender sender, int batchSize) {
        return new OutboxRelay(repository, sender, outboxTransactionTemplate, metrics, batchSize);
    }

    /** Records what it sent and can hold its first send until released, to keep a transaction open on purpose. */
    static final class GatedSender implements OutboxSender {

        private final OutboxSender delegate;
        private final boolean gateFirstSend;
        final CountDownLatch firstSendReached = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        final List<UUID> sent = Collections.synchronizedList(new ArrayList<>());

        GatedSender(OutboxSender delegate, boolean gateFirstSend) {
            this.delegate = delegate;
            this.gateFirstSend = gateFirstSend;
            if (!gateFirstSend) {
                release.countDown();
            }
        }

        @Override
        public void send(OutboxMessage message) {
            firstSendReached.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    throw new OutboxSendException("test gate was never released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OutboxSendException("interrupted", e);
            }
            delegate.send(message);
            sent.add(message.id());
        }

        void release() {
            release.countDown();
        }

        boolean gated() {
            return gateFirstSend;
        }
    }
}
