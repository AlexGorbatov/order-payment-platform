package com.altronixsoft.opp.payment.adapter.out.stripe;

import com.altronixsoft.opp.payment.application.GatewayErrorClass;
import com.altronixsoft.opp.payment.application.PaymentGateway;
import com.altronixsoft.opp.payment.application.PaymentGatewayException;
import com.altronixsoft.opp.payment.application.WebhookPayloadParser;
import com.altronixsoft.opp.payment.application.WebhookVerifier;
import com.stripe.StripeClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Stripe adapter. The application does not start without a test-mode API key ({@link LiveModeGuard}); the
 * {@link StripeClient} is built per instance from {@link StripeProperties}, never through the static
 * {@code Stripe.apiKey} (ADR-0012).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StripeProperties.class)
class StripeConfiguration {

    static final String CIRCUIT_BREAKER_NAME = "stripe";

    @Bean
    LiveModeGuard liveModeGuard(StripeProperties properties) {
        return LiveModeGuard.verify(properties.apiKey());
    }

    /** Depends on the guard: no client exists for a key that was not verified. */
    @Bean
    StripeClient stripeClient(StripeProperties properties, LiveModeGuard verified) {
        return newClient(properties);
    }

    @Bean
    CircuitBreaker stripeCircuitBreaker(StripeProperties properties, ObjectProvider<MeterRegistry> meters) {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        CircuitBreaker breaker = newCircuitBreaker(registry, properties.circuitBreaker());
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry)
                .bindTo(meters.getIfAvailable(SimpleMeterRegistry::new));
        return breaker;
    }

    @Bean
    PaymentGateway paymentGateway(
            StripeClient client, CircuitBreaker stripeCircuitBreaker, ObjectProvider<MeterRegistry> meters) {
        return newGateway(client, stripeCircuitBreaker, meters.getIfAvailable(SimpleMeterRegistry::new));
    }

    /** The signature timestamp is judged by the service's clock; outside a full application, by the system's. */
    @Bean
    WebhookVerifier webhookVerifier(StripeProperties properties, ObjectProvider<Clock> clock) {
        return new StripeWebhookVerifier(properties.webhook(), clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    WebhookPayloadParser webhookPayloadParser() {
        return new StripeWebhookPayloadParser();
    }

    // ------------------------------------------------------------------------ factories (also used by the tests)

    static StripeClient newClient(StripeProperties properties) {
        StripeClient.StripeClientBuilder builder = StripeClient.builder()
                .setApiKey(properties.apiKey())
                .setConnectTimeout((int) properties.connectTimeout().toMillis())
                .setReadTimeout((int) properties.readTimeout().toMillis())
                .setMaxNetworkRetries(properties.maxNetworkRetries());
        if (properties.apiBase() != null && !properties.apiBase().isBlank()) {
            builder.setApiBase(properties.apiBase().replaceAll("/+$", ""));
        }
        return builder.build();
    }

    /** Only {@code TRANSIENT} failures count against the breaker; a rejected request means Stripe is healthy. */
    static CircuitBreaker newCircuitBreaker(
            CircuitBreakerRegistry registry, StripeProperties.CircuitBreakerProperties properties) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.slidingWindowSize())
                .minimumNumberOfCalls(properties.minimumNumberOfCalls())
                .failureRateThreshold(properties.failureRateThreshold())
                .waitDurationInOpenState(properties.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(properties.permittedCallsInHalfOpenState())
                .recordException(failure -> failure instanceof PaymentGatewayException gateway
                        && gateway.errorClass() == GatewayErrorClass.TRANSIENT)
                .build();
        return registry.circuitBreaker(CIRCUIT_BREAKER_NAME, config);
    }

    static PaymentGateway newGateway(StripeClient client, CircuitBreaker breaker, MeterRegistry meters) {
        return new StripePaymentGateway(client, breaker, new StripeMetrics(meters));
    }
}
