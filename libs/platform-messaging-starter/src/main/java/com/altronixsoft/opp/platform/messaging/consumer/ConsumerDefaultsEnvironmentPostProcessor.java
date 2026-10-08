package com.altronixsoft.opp.platform.messaging.consumer;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Supplies the consumer defaults of the platform as the <b>lowest-priority</b> property source, so every value the
 * service sets in {@code application.yml} or the environment still wins.
 *
 * <ul>
 *   <li>keys and values are read through {@code ErrorHandlingDeserializer} (delegate {@code StringDeserializer}): a
 *       record that cannot be deserialized becomes a handled error and reaches the DLT instead of crashing the
 *       consumer;
 *   <li>{@code enable.auto.commit=false} and listener ack mode {@code RECORD}: the offset is committed only after the
 *       listener (and its transaction) returned for that record;
 *   <li>{@code isolation.level=read_committed}: never read records of aborted producer transactions;
 *   <li>{@code auto.offset.reset=earliest}: a new consumer group must not skip events published before it first joined.
 * </ul>
 *
 * Switch off with {@code platform.consumer.enabled=false} or {@code platform.consumer.defaults.enabled=false}.
 */
public class ConsumerDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "platformConsumerDefaults";

    private static final String ERROR_HANDLING_DESERIALIZER =
            "org.springframework.kafka.support.serializer.ErrorHandlingDeserializer";
    private static final String STRING_DESERIALIZER = "org.apache.kafka.common.serialization.StringDeserializer";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty("platform.consumer.enabled", Boolean.class, true)
                || !environment.getProperty("platform.consumer.defaults.enabled", Boolean.class, true)) {
            return;
        }
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("spring.kafka.consumer.key-deserializer", ERROR_HANDLING_DESERIALIZER);
        defaults.put("spring.kafka.consumer.value-deserializer", ERROR_HANDLING_DESERIALIZER);
        defaults.put("spring.kafka.consumer.properties.spring.deserializer.key.delegate.class", STRING_DESERIALIZER);
        defaults.put("spring.kafka.consumer.properties.spring.deserializer.value.delegate.class", STRING_DESERIALIZER);
        defaults.put("spring.kafka.consumer.enable-auto-commit", "false");
        defaults.put("spring.kafka.consumer.isolation-level", "read-committed");
        defaults.put("spring.kafka.consumer.auto-offset-reset", "earliest");
        defaults.put("spring.kafka.listener.ack-mode", "record");
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
