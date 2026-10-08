package com.altronixsoft.opp.platform.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

class OutboxFlywayLocationTest {

    private static final Location PLATFORM = Location.fromPath("classpath:", "db/migration/platform");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxAutoConfiguration.class))
            .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(KafkaProperties.class, KafkaProperties::new)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withPropertyValues("platform.outbox.relay.initial-delay=1h", "platform.outbox.cleanup.fixed-delay=1h");

    @Test
    void addsThePlatformLocationToACustomizedLocationList() {
        runner.run(context -> {
            FluentConfiguration configuration = Flyway.configure().locations("classpath:db/service-only");

            context.getBean(FlywayConfigurationCustomizer.class).customize(configuration);

            assertThat(configuration.getLocations())
                    .containsExactlyInAnyOrder(Location.fromPath("classpath:", "db/service-only"), PLATFORM);
        });
    }

    @Test
    void doesNotDuplicateALocationThatIsAlreadyCovered() {
        runner.run(context -> {
            // Flyway collapses a location nested in a listed one, so the default db/migration already covers it.
            FluentConfiguration configuration =
                    Flyway.configure().locations("classpath:db/migration", "classpath:db/migration/platform");

            context.getBean(FlywayConfigurationCustomizer.class).customize(configuration);

            assertThat(configuration.getLocations()).containsExactly(Location.fromPath("classpath:", "db/migration"));
        });
    }
}
