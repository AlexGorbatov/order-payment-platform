package com.altronixsoft.opp.platform.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class ConsumerDefaultsEnvironmentPostProcessorTest {

    private final ConsumerDefaultsEnvironmentPostProcessor processor = new ConsumerDefaultsEnvironmentPostProcessor();

    @Test
    void suppliesTheReliableConsumerDefaults() {
        StandardEnvironment environment = new StandardEnvironment();

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.kafka.consumer.enable-auto-commit"))
                .isEqualTo("false");
        assertThat(environment.getProperty("spring.kafka.consumer.isolation-level"))
                .isEqualTo("read-committed");
        assertThat(environment.getProperty("spring.kafka.consumer.auto-offset-reset"))
                .isEqualTo("earliest");
        assertThat(environment.getProperty("spring.kafka.listener.ack-mode")).isEqualTo("record");
        assertThat(environment.getProperty("spring.kafka.consumer.key-deserializer"))
                .isEqualTo("org.springframework.kafka.support.serializer.ErrorHandlingDeserializer");
        assertThat(environment.getProperty("spring.kafka.consumer.value-deserializer"))
                .isEqualTo("org.springframework.kafka.support.serializer.ErrorHandlingDeserializer");
        assertThat(environment.getProperty("spring.kafka.consumer.properties.spring.deserializer.value.delegate.class"))
                .isEqualTo("org.apache.kafka.common.serialization.StringDeserializer");
        assertThat(environment.getProperty("spring.kafka.consumer.properties.spring.deserializer.key.delegate.class"))
                .isEqualTo("org.apache.kafka.common.serialization.StringDeserializer");
    }

    @Test
    void valuesTheServiceSetsAlwaysWin() {
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource(
                        "application",
                        Map.of(
                                "spring.kafka.consumer.auto-offset-reset", "latest",
                                "spring.kafka.listener.ack-mode", "batch")));

        processor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.kafka.consumer.auto-offset-reset"))
                .isEqualTo("latest");
        assertThat(environment.getProperty("spring.kafka.listener.ack-mode")).isEqualTo("batch");
        assertThat(environment.getProperty("spring.kafka.consumer.enable-auto-commit"))
                .isEqualTo("false");
    }

    @Test
    void canBeSwitchedOff() {
        for (String property : new String[] {"platform.consumer.enabled", "platform.consumer.defaults.enabled"}) {
            StandardEnvironment environment = new StandardEnvironment();
            environment.getPropertySources().addFirst(new MapPropertySource("application", Map.of(property, "false")));

            processor.postProcessEnvironment(environment, null);

            assertThat(environment.getProperty("spring.kafka.consumer.enable-auto-commit"))
                    .as(property)
                    .isNull();
        }
    }
}
