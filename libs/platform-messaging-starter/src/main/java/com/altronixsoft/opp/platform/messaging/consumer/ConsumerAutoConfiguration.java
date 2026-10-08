package com.altronixsoft.opp.platform.messaging.consumer;

import com.altronixsoft.opp.contracts.EventSerde;
import com.altronixsoft.opp.contracts.EventSerdeException;
import jakarta.validation.ValidationException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.retrytopic.RetryTopicConfiguration;
import org.springframework.kafka.retrytopic.RetryTopicConfigurationBuilder;
import org.springframework.kafka.retrytopic.RetryTopicConfigurationSupport;
import org.springframework.kafka.support.EndpointHandlerMethod;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Consumer reliability for every {@code @KafkaListener} of the service (architecture §7.4, ADR-0007):
 *
 * <ol>
 *   <li>a failed record is retried on the source topic {@code blocking-retries} times, {@code blocking-interval} apart;
 *   <li>then it moves through the retry topics {@code <topic>-retry-0 … -N-1}, waiting {@code topic-delays[i]} before
 *       each attempt, without blocking the partition;
 *   <li>then it lands on {@code <topic>-dlt}, from where the {@code DeadLetterPersister} stores it.
 * </ol>
 *
 * Deserialization errors, {@link NonRetryableEventException}, {@link EventSerdeException} and bean-validation errors
 * skip steps 1 and 2. Consumer client defaults come from {@link ConsumerDefaultsEnvironmentPostProcessor}. Disable
 * everything with {@code platform.consumer.enabled=false}; a service-defined {@code RetryTopicConfiguration} or
 * {@code RetryTopicConfigurationSupport} replaces the platform's.
 */
@AutoConfiguration(afterName = "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration")
@ConditionalOnClass({KafkaTemplate.class, RetryTopicConfigurationSupport.class})
@ConditionalOnProperty(prefix = "platform.consumer", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(ConsumerProperties.class)
public class ConsumerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    EventSerde eventSerde() {
        return new EventSerde();
    }

    @Bean
    @ConditionalOnMissingBean
    EventEnvelopeReader eventEnvelopeReader(EventSerde serde) {
        return new EventEnvelopeReader(serde);
    }

    @Bean
    @ConditionalOnMissingBean
    DeadLetterLogger deadLetterLogger() {
        return new DeadLetterLogger();
    }

    @Bean
    @ConditionalOnMissingBean
    DltKafkaOperations platformDltKafkaOperations(KafkaProperties kafkaProperties) {
        return new DltKafkaOperations(kafkaProperties.buildProducerProperties());
    }

    @Bean
    @ConditionalOnMissingBean(RetryTopicConfiguration.class)
    RetryTopicConfiguration platformRetryTopicConfiguration(
            ConsumerProperties properties, DltKafkaOperations dltOperations) {
        ConsumerProperties.Retry retry = properties.retry();
        return RetryTopicConfigurationBuilder.newInstance()
                .customBackoff(new SequenceBackOff(retry.topicDelays()))
                .maxAttempts(retry.topicDelays().size() + 1)
                .suffixTopicsWithIndexValues()
                .autoCreateTopics(retry.autoCreateTopics(), retry.topicPartitions(), retry.topicReplicationFactor())
                .notRetryOn(nonRetryableExceptions(retry))
                .traversingCauses()
                .dltHandlerMethod(new EndpointHandlerMethod(DeadLetterLogger.class, "onDeadLetter"))
                .create(dltOperations.template());
    }

    static List<Class<? extends Throwable>> nonRetryableExceptions(ConsumerProperties.Retry retry) {
        List<Class<? extends Throwable>> types = new ArrayList<>(List.of(
                DeserializationException.class,
                NonRetryableEventException.class,
                EventSerdeException.class,
                ValidationException.class));
        types.addAll(retry.nonRetryable());
        return types;
    }

    /** Configures the blocking retries on the source topic and the fatal exception list of the retry-topic machinery. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(RetryTopicConfigurationSupport.class)
    static class PlatformRetryTopicSupport extends RetryTopicConfigurationSupport {

        private final ConsumerProperties properties;

        PlatformRetryTopicSupport(ConsumerProperties properties) {
            this.properties = properties;
        }

        @Override
        protected void configureBlockingRetries(BlockingRetriesConfigurer blockingRetries) {
            ConsumerProperties.Retry retry = properties.retry();
            blockingRetries
                    .retryOn(Exception.class)
                    .backOff(new FixedBackOff(retry.blockingInterval().toMillis(), retry.blockingRetries()));
        }

        /**
         * {@code retryOn(Exception.class)} makes everything retryable on the source topic; the exceptions that must go
         * straight to the DLT are carved out again here.
         */
        @Override
        protected void configureCustomizers(CustomizersConfigurer customizers) {
            @SuppressWarnings("unchecked")
            Class<? extends Exception>[] nonRetryable = nonRetryableExceptions(properties.retry()).stream()
                    .map(type -> type.asSubclass(Exception.class))
                    .toArray(Class[]::new);
            customizers.customizeErrorHandler(handler -> handler.addNotRetryableExceptions(nonRetryable));
        }

        @Override
        protected void manageNonBlockingFatalExceptions(List<Class<? extends Throwable>> nonBlockingFatalExceptions) {
            // The framework already lists some (DeserializationException); duplicates make it fail at startup.
            for (Class<? extends Throwable> type : nonRetryableExceptions(properties.retry())) {
                if (!nonBlockingFatalExceptions.contains(type)) {
                    nonBlockingFatalExceptions.add(type);
                }
            }
        }
    }
}
