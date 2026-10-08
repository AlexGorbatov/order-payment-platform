package com.altronixsoft.opp.platform.messaging.deadletter;

import com.altronixsoft.opp.platform.messaging.outbox.OutboxAutoConfiguration;
import com.altronixsoft.opp.platform.messaging.outbox.OutboxPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authorization.method.AuthorizationAdvisor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.web.bind.annotation.RestController;

/**
 * Persists dead letters and exposes the operator API. Disable with {@code platform.dead-letters.enabled=false}.
 *
 * <ul>
 *   <li>The <b>persister</b> is a plain message-listener container subscribed to the pattern {@code .*-dlt} (not an
 *       {@code @KafkaListener}, so the retry-topic machinery does not wrap it). It must not lose a record: when the
 *       database is unavailable it retries the same record indefinitely instead of skipping it.
 *   <li>The <b>admin API</b> needs the outbox (replay publishes through it), a servlet application and Spring Security
 *       with method security enabled.
 * </ul>
 */
@AutoConfiguration(
        after = OutboxAutoConfiguration.class,
        afterName = {
            "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration",
            "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
            "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration",
            "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration",
            "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration"
        })
@ConditionalOnProperty(prefix = "platform.dead-letters", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(DeadLetterProperties.class)
public class DeadLetterAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    DeadLetterRepository deadLetterRepository(JdbcClient jdbcClient) {
        return new DeadLetterRepository(jdbcClient);
    }

    @Bean
    @ConditionalOnMissingBean
    DeadLetterPersister deadLetterPersister(DeadLetterRepository repository, ObjectProvider<MeterRegistry> meters) {
        return new DeadLetterPersister(repository, meters.getIfAvailable(SimpleMeterRegistry::new));
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(KafkaTemplate.class)
    @ConditionalOnProperty(prefix = "platform.dead-letters.persister", name = "enabled", matchIfMissing = true)
    static class PersisterConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "deadLetterPersisterContainer")
        KafkaMessageListenerContainer<byte[], byte[]> deadLetterPersisterContainer(
                KafkaProperties kafkaProperties,
                DeadLetterProperties properties,
                DeadLetterPersister persister,
                Environment environment) {
            DeadLetterProperties.Persister settings = properties.persister();
            String groupId = settings.groupId().isBlank()
                    ? environment.getProperty("spring.application.name", "platform") + "-dlt-persister"
                    : settings.groupId();

            Map<String, Object> config = kafkaProperties.buildConsumerProperties();
            config.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
            config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
            config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
            config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
            config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
            config.put(ConsumerConfig.METADATA_MAX_AGE_CONFIG, (int)
                    settings.metadataRefresh().toMillis());

            ContainerProperties container = new ContainerProperties(Pattern.compile(settings.topicPattern()));
            container.setGroupId(groupId);
            container.setAckMode(ContainerProperties.AckMode.RECORD);
            container.setMessageListener((MessageListener<byte[], byte[]>) persister::persist);

            KafkaMessageListenerContainer<byte[], byte[]> listener =
                    new KafkaMessageListenerContainer<>(new DefaultKafkaConsumerFactory<>(config), container);
            // A dead letter must never be skipped: wait for the database as long as it takes.
            listener.setCommonErrorHandler(
                    new DefaultErrorHandler(new FixedBackOff(1_000L, FixedBackOff.UNLIMITED_ATTEMPTS)));
            listener.setBeanName("deadLetterPersisterContainer");
            return listener;
        }
    }

    /** The admin API: only with the outbox (for replay), a servlet application and Spring Security present. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass({RestController.class, PreAuthorize.class})
    @ConditionalOnBean(OutboxPublisher.class)
    @ConditionalOnProperty(prefix = "platform.dead-letters.admin", name = "enabled", matchIfMissing = true)
    static class AdminConfiguration {

        @Bean
        @ConditionalOnMissingBean
        DeadLetterService deadLetterService(
                DeadLetterRepository repository,
                OutboxPublisher outbox,
                PlatformTransactionManager transactionManager) {
            return new DeadLetterService(repository, outbox, new TransactionTemplate(transactionManager));
        }

        @Bean
        @ConditionalOnMissingBean
        DeadLetterAdminController deadLetterAdminController(DeadLetterService service) {
            return new DeadLetterAdminController(service);
        }

        /**
         * Fail closed: {@code @PreAuthorize} on the controller is only enforced when method security is enabled, which
         * is the service's responsibility. Without it the endpoints would be open to every authenticated user.
         */
        @Bean
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        static SmartInitializingSingleton deadLetterMethodSecurityGuard(ListableBeanFactory beanFactory) {
            return () -> {
                if (beanFactory.getBeanNamesForType(AuthorizationAdvisor.class).length == 0) {
                    throw new IllegalStateException(
                            "The dead-letter admin API (/admin/dead-letters) is secured with @PreAuthorize, but method "
                                    + "security is not enabled. Add @EnableMethodSecurity to a configuration class, or "
                                    + "set platform.dead-letters.admin.enabled=false.");
                }
            };
        }
    }
}
