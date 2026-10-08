package com.altronixsoft.opp.platform.messaging.consumer;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;

/**
 * The producer Spring Kafka uses to forward failed records to retry and dead-letter topics.
 *
 * <p>It is private to the starter (not a {@code KafkaTemplate} bean), so it neither displaces the service's own
 * template nor inherits its serializers. Values are forwarded as they were received: text as {@code String}, and the raw
 * bytes of records that could not be deserialized as {@code byte[]}.
 */
final class DltKafkaOperations implements DisposableBean {

    private final DefaultKafkaProducerFactory<Object, Object> producerFactory;
    private final KafkaTemplate<Object, Object> template;

    DltKafkaOperations(Map<String, Object> baseProducerProperties) {
        Map<String, Object> props = new HashMap<>(baseProducerProperties);
        props.remove(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG);
        props.remove(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        this.producerFactory = new DefaultKafkaProducerFactory<>(props, byTypeSerializer(), byTypeSerializer());
        this.template = new KafkaTemplate<>(producerFactory);
    }

    KafkaTemplate<Object, Object> template() {
        return template;
    }

    private static Serializer<Object> byTypeSerializer() {
        Map<Class<?>, Serializer<?>> delegates = new HashMap<>();
        delegates.put(String.class, new StringSerializer());
        delegates.put(byte[].class, new ByteArraySerializer());
        return new DelegatingByTypeSerializer(delegates, true);
    }

    @Override
    public void destroy() {
        producerFactory.destroy();
    }
}
