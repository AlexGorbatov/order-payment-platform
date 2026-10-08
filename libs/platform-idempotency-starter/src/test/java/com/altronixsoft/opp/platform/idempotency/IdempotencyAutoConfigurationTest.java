package com.altronixsoft.opp.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.unit.DataSize;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class IdempotencyAutoConfigurationTest {

    private final WebApplicationContextRunner web = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IdempotencyAutoConfiguration.class))
            .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withPropertyValues("platform.idempotency.cleanup.fixed-delay=1h");

    @Test
    void contributesTheFilterTheInterceptorAndTheCleanupWithDocumentedDefaults() {
        web.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context)
                    .hasSingleBean(IdempotencyInterceptor.class)
                    .hasSingleBean(IdempotencyRepository.class)
                    .hasSingleBean(IdempotencyCleanup.class)
                    .hasSingleBean(WebMvcConfigurer.class);
            IdempotencyProperties properties = context.getBean(IdempotencyProperties.class);
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.maxBodySize()).isEqualTo(DataSize.ofMegabytes(1));
            assertThat(properties.inProgressTimeout()).isEqualTo(Duration.ofMinutes(2));
            assertThat(properties.replayHeaders())
                    .containsExactly("Content-Type", "Location", "Content-Language", "ETag");
            assertThat(properties.filterOrder()).isZero();
            assertThat(properties.cleanup().fixedDelay()).isEqualTo(Duration.ofSeconds(3600));
            assertThat(properties.cleanup().batchSize()).isEqualTo(1000);
            @SuppressWarnings("unchecked")
            FilterRegistrationBean<IdempotencyBufferingFilter> filter = context.getBean(FilterRegistrationBean.class);
            assertThat(filter.getOrder())
                    .as("after Spring Security's chain (-100)")
                    .isGreaterThan(-100);
        });
    }

    @Test
    void theSpringSecurityPrincipalResolverIsUsedWhenSecurityIsOnTheClasspath() {
        web.run(context ->
                assertThat(context.getBean(PrincipalResolver.class)).isInstanceOf(SecurityPrincipalResolver.class));
    }

    @Test
    void aServiceDefinedPrincipalResolverWins() {
        PrincipalResolver custom = () -> "tenant-7";

        web.withBean(PrincipalResolver.class, () -> custom).run(context -> {
            assertThat(context).hasSingleBean(PrincipalResolver.class);
            assertThat(context.getBean(PrincipalResolver.class)).isSameAs(custom);
        });
    }

    @Test
    void cleanupCanBeSwitchedOff() {
        web.withPropertyValues("platform.idempotency.cleanup.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(IdempotencyCleanup.class);
            assertThat(context).hasSingleBean(IdempotencyInterceptor.class);
        });
    }

    @Test
    void masterSwitchRemovesEverything() {
        web.withPropertyValues("platform.idempotency.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(IdempotencyInterceptor.class)
                        .doesNotHaveBean(IdempotencyRepository.class)
                        .doesNotHaveBean(IdempotencyCleanup.class)
                        .doesNotHaveBean(FilterRegistrationBean.class));
    }

    @Test
    void nonWebApplicationsGetNothing() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(IdempotencyAutoConfiguration.class))
                .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .run(context -> assertThat(context).doesNotHaveBean(IdempotencyInterceptor.class));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "platform.idempotency.in-progress-timeout=1s",
                "platform.idempotency.cleanup.batch-size=0",
                "platform.idempotency.cleanup.fixed-delay=0s",
                "platform.idempotency.replay-headers="
            })
    void invalidSettingsFailStartup(String property) {
        web.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }

    @Idempotent(ttl = "next week")
    void brokenEndpoint() {}

    @Test
    void anUnusableTtlOnAHandlerMethodFailsStartupInsteadOfTheFirstRequest() throws Exception {
        Method method = IdempotencyAutoConfigurationTest.class.getDeclaredMethod("brokenEndpoint");
        RequestMappingHandlerMapping mapping = mock(RequestMappingHandlerMapping.class);
        when(mapping.getHandlerMethods())
                .thenReturn(Map.of(
                        RequestMappingInfo.paths("/broken").build(),
                        new HandlerMethod(new IdempotencyAutoConfigurationTest(), method)));

        web.withBean(RequestMappingHandlerMapping.class, () -> mapping).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("ISO-8601");
        });
    }
}
