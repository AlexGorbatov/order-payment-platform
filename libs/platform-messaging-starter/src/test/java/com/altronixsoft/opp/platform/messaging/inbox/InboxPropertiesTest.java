package com.altronixsoft.opp.platform.messaging.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class InboxPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(InboxProperties.class)
    static class Props {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Props.class);

    @Test
    void retentionDefaultsToFourteenDaysWhichExceedsTheTopicRetention() {
        runner.run(context -> {
            InboxProperties properties = context.getBean(InboxProperties.class);
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.cleanup().retention()).isEqualTo(Duration.ofDays(14));
            assertThat(properties.cleanup().batchSize()).isEqualTo(1000);
        });
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "platform.inbox.cleanup.retention=1h",
                "platform.inbox.cleanup.batch-size=0",
                "platform.inbox.cleanup.fixed-delay=0s"
            })
    void invalidSettingsFailStartup(String property) {
        runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }
}
