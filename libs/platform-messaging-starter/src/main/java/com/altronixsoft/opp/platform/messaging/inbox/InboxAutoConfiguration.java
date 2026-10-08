package com.altronixsoft.opp.platform.messaging.inbox;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Wires the consumer inbox. Disable with {@code platform.inbox.enabled=false}. */
@AutoConfiguration(
        afterName = {
            "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration",
            "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
            "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration",
            "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration"
        })
@ConditionalOnProperty(prefix = "platform.inbox", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(InboxProperties.class)
public class InboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    InboxRepository inboxRepository(JdbcClient jdbcClient) {
        return new InboxRepository(jdbcClient);
    }

    @Bean
    @ConditionalOnMissingBean
    InboxGuard inboxGuard(
            InboxRepository repository,
            PlatformTransactionManager transactionManager,
            ObjectProvider<MeterRegistry> meters) {
        return new InboxGuard(
                repository,
                new TransactionTemplate(transactionManager),
                meters.getIfAvailable(SimpleMeterRegistry::new));
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "platform.inbox.cleanup", name = "enabled", matchIfMissing = true)
    @EnableScheduling
    static class CleanupConfiguration {

        @Bean
        @ConditionalOnMissingBean
        InboxCleanup inboxCleanup(
                InboxRepository repository, PlatformTransactionManager transactionManager, InboxProperties properties) {
            return new InboxCleanup(
                    repository,
                    new TransactionTemplate(transactionManager),
                    properties.cleanup().retention(),
                    properties.cleanup().batchSize());
        }
    }
}
