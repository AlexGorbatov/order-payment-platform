package com.altronixsoft.opp.platform.idempotency;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.flywaydb.core.api.Location;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Wires HTTP idempotency for servlet applications with Spring MVC. Disable with {@code platform.idempotency.enabled=false}.
 *
 * <p>Two cooperating pieces: {@link IdempotencyBufferingFilter} (registered after Spring Security's filter chain)
 * buffers the request body and the response of requests that carry an {@code Idempotency-Key}, and
 * {@link IdempotencyInterceptor} applies the algorithm to handler methods annotated with {@link Idempotent}. The
 * caller's principal comes from the Spring Security context when Spring Security is present, otherwise every caller is
 * {@code anonymous}. Provide your own {@link PrincipalResolver} bean to change that.
 */
@AutoConfiguration(
        afterName = {
            "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration",
            "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
            "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration",
            "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration"
        })
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({HandlerInterceptor.class, JdbcClient.class})
@ConditionalOnProperty(prefix = "platform.idempotency", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    IdempotencyRepository idempotencyRepository(JdbcClient jdbcClient, PlatformTransactionManager transactionManager) {
        return new IdempotencyRepository(jdbcClient, transactionManager);
    }

    @Bean
    @ConditionalOnMissingBean
    IdempotencyInterceptor idempotencyInterceptor(
            IdempotencyRepository repository,
            PrincipalResolver principals,
            IdempotencyProperties properties,
            Environment environment,
            ObjectProvider<MeterRegistry> meters) {
        return new IdempotencyInterceptor(
                repository, principals, properties, environment, meters.getIfAvailable(SimpleMeterRegistry::new));
    }

    @Bean
    FilterRegistrationBean<IdempotencyBufferingFilter> idempotencyBufferingFilter(IdempotencyProperties properties) {
        FilterRegistrationBean<IdempotencyBufferingFilter> registration =
                new FilterRegistrationBean<>(new IdempotencyBufferingFilter(properties.maxBodySize()));
        registration.setName("idempotencyBufferingFilter");
        registration.setOrder(properties.filterOrder());
        return registration;
    }

    @Bean
    WebMvcConfigurer idempotencyWebMvcConfigurer(IdempotencyInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor);
            }
        };
    }

    /**
     * Fails startup on an unusable {@code ttl} instead of failing the first request: every {@code @Idempotent} handler
     * method is resolved once.
     */
    @Bean
    static SmartInitializingSingleton idempotentAnnotationValidator(
            ObjectProvider<RequestMappingHandlerMapping> mappings, Environment environment) {
        return () -> mappings.orderedStream()
                .flatMap(mapping -> mapping.getHandlerMethods().values().stream())
                .forEach(method -> IdempotencyInterceptor.annotationOf(method)
                        .ifPresent(annotation -> IdempotencySettings.of(annotation, environment)));
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.security.core.context.SecurityContextHolder")
    static class SecurityConfiguration {

        @Bean
        @ConditionalOnMissingBean(PrincipalResolver.class)
        PrincipalResolver securityPrincipalResolver() {
            return new SecurityPrincipalResolver();
        }
    }

    @Bean
    @ConditionalOnMissingBean(PrincipalResolver.class)
    PrincipalResolver anonymousPrincipalResolver() {
        return () -> PrincipalResolver.ANONYMOUS;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "platform.idempotency.cleanup", name = "enabled", matchIfMissing = true)
    @EnableScheduling
    static class CleanupConfiguration {

        @Bean
        @ConditionalOnMissingBean
        IdempotencyCleanup idempotencyCleanup(IdempotencyRepository repository, IdempotencyProperties properties) {
            return new IdempotencyCleanup(repository, properties.cleanup().batchSize());
        }
    }

    /**
     * Adds the starter's migration location to Flyway, so a service that customizes {@code spring.flyway.locations}
     * still gets {@code idempotency_record}.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({FlywayConfigurationCustomizer.class, Location.class})
    static class FlywayConfiguration {

        @Bean
        FlywayConfigurationCustomizer idempotencyFlywayLocation() {
            return configuration -> {
                Location platform = Location.fromPath("classpath:", "db/migration/platform");
                List<Location> locations = new ArrayList<>(Arrays.asList(configuration.getLocations()));
                if (!locations.contains(platform)) {
                    locations.add(platform);
                    configuration.locations(locations.toArray(Location[]::new));
                }
            };
        }
    }
}
