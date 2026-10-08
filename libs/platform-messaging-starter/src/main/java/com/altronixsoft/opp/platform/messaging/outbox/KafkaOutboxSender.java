package com.altronixsoft.opp.platform.messaging.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Sends outbox rows with a dedicated, idempotent producer ({@code acks=all}, {@code enable.idempotence=true}) and waits
 * for the acknowledgement of every record.
 *
 * <p>The producer is private to the outbox, so the service's own Kafka configuration (serializers, transactions) cannot
 * weaken these guarantees. Its delivery timeout is bound to the ack timeout: when a record cannot be acknowledged the
 * client gives up at about the same moment the relay does, which keeps the window in which a "failed" record could still
 * reach the broker as small as possible (a late duplicate is absorbed by the consumer inbox in any case).
 */
public class KafkaOutboxSender implements OutboxSender, DisposableBean {

    private final DefaultKafkaProducerFactory<String, String> producerFactory;
    private final KafkaTemplate<String, String> template;
    private final Duration ackTimeout;

    /**
     * @param baseProducerProperties the service's {@code spring.kafka.*} producer settings (bootstrap servers, security)
     */
    public KafkaOutboxSender(Map<String, Object> baseProducerProperties, Duration ackTimeout) {
        this.ackTimeout = ackTimeout;
        this.producerFactory =
                new DefaultKafkaProducerFactory<>(producerProperties(baseProducerProperties, ackTimeout));
        this.template = new KafkaTemplate<>(producerFactory);
    }

    static Map<String, Object> producerProperties(Map<String, Object> base, Duration ackTimeout) {
        long ackMs = ackTimeout.toMillis();
        Map<String, Object> props = new HashMap<>(base);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) ackMs);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) Math.max(100, ackMs / 2));
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, (int) ackMs);
        return props;
    }

    @Override
    public void send(OutboxMessage message) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(message.topic(), null, message.partitionKey(), message.payload());
        message.headers().forEach((name, value) -> record.headers().add(name, value.getBytes(StandardCharsets.UTF_8)));
        try {
            template.send(record).get(ackTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OutboxSendException("Interrupted while waiting for the acknowledgement of " + message.id(), e);
        } catch (TimeoutException e) {
            throw new OutboxSendException(
                    "No acknowledgement from Kafka within " + ackTimeout.toMillis() + " ms for " + message.id(), e);
        } catch (ExecutionException e) {
            throw new OutboxSendException("Kafka rejected " + message.id() + ": " + rootMessage(e), e.getCause());
        } catch (RuntimeException e) {
            throw new OutboxSendException("Cannot send " + message.id() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void destroy() {
        producerFactory.destroy();
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
}
