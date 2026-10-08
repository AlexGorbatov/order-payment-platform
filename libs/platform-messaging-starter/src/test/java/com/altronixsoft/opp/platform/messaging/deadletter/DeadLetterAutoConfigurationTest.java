package com.altronixsoft.opp.platform.messaging.deadletter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.altronixsoft.opp.platform.messaging.outbox.OutboxPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.transaction.PlatformTransactionManager;

class DeadLetterAutoConfigurationTest {

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class MethodSecurity {}

    private static <T extends org.springframework.boot.test.context.runner.AbstractApplicationContextRunner<T, ?, ?>>
            T withInfrastructure(T runner) {
        return runner.withBean(JdbcClient.class, () -> mock(JdbcClient.class))
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean(KafkaProperties.class, KafkaProperties::new)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(OutboxPublisher.class, () -> mock(OutboxPublisher.class))
                // no broker in these tests: the persister container would try to connect on startup
                .withPropertyValues("platform.dead-letters.persister.enabled=false");
    }

    private final WebApplicationContextRunner web = withInfrastructure(new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DeadLetterAutoConfiguration.class)));

    @Test
    void exposesTheAdminApiWhenMethodSecurityIsEnabled() {
        web.withUserConfiguration(MethodSecurity.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context)
                    .hasSingleBean(DeadLetterAdminController.class)
                    .hasSingleBean(DeadLetterService.class)
                    .hasSingleBean(DeadLetterRepository.class)
                    .hasSingleBean(DeadLetterPersister.class);
        });
    }

    @Test
    void refusesToStartWithoutMethodSecuritySoTheEndpointsCannotBeExposedUnguarded() {
        web.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("@EnableMethodSecurity");
        });
    }

    @Test
    void theAdminApiCanBeSwitchedOffAndThenNoSecurityIsRequired() {
        web.withPropertyValues("platform.dead-letters.admin.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(DeadLetterAdminController.class);
            assertThat(context).hasSingleBean(DeadLetterPersister.class);
        });
    }

    @Test
    void nonWebApplicationsGetNoAdminApiButStillPersistDeadLetters() {
        withInfrastructure(new ApplicationContextRunner()
                        .withConfiguration(AutoConfigurations.of(DeadLetterAutoConfiguration.class)))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(DeadLetterAdminController.class);
                    assertThat(context).hasSingleBean(DeadLetterPersister.class);
                });
    }

    @Test
    void noAdminApiWithoutTheOutboxBecauseReplayGoesThroughIt() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DeadLetterAutoConfiguration.class))
                .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean(KafkaProperties.class, KafkaProperties::new)
                .withPropertyValues("platform.dead-letters.persister.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(DeadLetterAdminController.class);
                });
    }

    @Test
    void masterSwitchRemovesEverything() {
        web.withPropertyValues("platform.dead-letters.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(DeadLetterRepository.class)
                        .doesNotHaveBean(DeadLetterPersister.class)
                        .doesNotHaveBean(DeadLetterAdminController.class));
    }

    @Test
    void persisterSettingsAreValidated() {
        web.withPropertyValues("platform.dead-letters.persister.metadata-refresh=10ms")
                .run(context -> assertThat(context).hasFailed());
        web.withPropertyValues("platform.dead-letters.persister.topic-pattern=")
                .run(context -> assertThat(context).hasFailed());
    }
}
