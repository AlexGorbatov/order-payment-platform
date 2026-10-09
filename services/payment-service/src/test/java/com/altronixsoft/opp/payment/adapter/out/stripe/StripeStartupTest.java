package com.altronixsoft.opp.payment.adapter.out.stripe;

import static org.assertj.core.api.Assertions.assertThat;

import com.altronixsoft.opp.payment.application.PaymentGateway;
import com.stripe.StripeClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The Stripe adapter's wiring, and that it cannot start with a live (or any non-test) key. */
class StripeStartupTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withUserConfiguration(StripeConfiguration.class);

    @Test
    void startsWithATestKeyAndExposesTheGateway() {
        runner.withPropertyValues("stripe.api-key=sk_test_startup").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(PaymentGateway.class);
            assertThat(context).hasSingleBean(StripeClient.class);
            assertThat(context).hasSingleBean(LiveModeGuard.class);
            assertThat(context.getBean("stripeCircuitBreaker", CircuitBreaker.class)
                            .getName())
                    .isEqualTo("stripe");
        });
    }

    @Test
    void aRestrictedTestKeyIsFine() {
        runner.withPropertyValues("stripe.api-key=rk_test_restricted")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void refusesToStartWithALiveKey() {
        runner.withPropertyValues("stripe.api-key=sk_live_SUPERSECRETVALUE123").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(InvalidStripeKeyException.class);
            Throwable root = rootCause(context.getStartupFailure());
            assertThat(root.getMessage())
                    .contains("Refusing to start")
                    .contains("sk_live_")
                    .doesNotContain("SUPERSECRETVALUE123");
        });
    }

    @Test
    void refusesToStartWithARestrictedLiveKey() {
        runner.withPropertyValues("stripe.api-key=rk_live_abcdef").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(InvalidStripeKeyException.class);
        });
    }

    @Test
    void refusesToStartWithoutAKey() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure()))
                    .isInstanceOfSatisfying(
                            InvalidStripeKeyException.class,
                            e -> assertThat(e.reason()).isEqualTo(InvalidStripeKeyException.Reason.MISSING));
        });
    }

    @Test
    void theSettingsHaveSafeDefaultsAndBindFromProperties() {
        runner.withPropertyValues(
                        "stripe.api-key=sk_test_props",
                        "stripe.api-base=http://localhost:12111",
                        "stripe.read-timeout=7s",
                        "stripe.circuit-breaker.sliding-window-size=8")
                .run(context -> {
                    StripeProperties properties = context.getBean(StripeProperties.class);
                    assertThat(properties.apiBase()).isEqualTo("http://localhost:12111");
                    assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(7));
                    assertThat(properties.maxNetworkRetries()).isEqualTo(2);
                    assertThat(properties.circuitBreaker().slidingWindowSize()).isEqualTo(8);
                    assertThat(properties.circuitBreaker().minimumNumberOfCalls())
                            .isEqualTo(10);
                    assertThat(properties.circuitBreaker().waitDurationInOpenState())
                            .isEqualTo(Duration.ofSeconds(30));
                });
    }

    @Test
    void thePropertiesNeverPrintTheKey() {
        runner.withPropertyValues("stripe.api-key=sk_test_SUPERSECRETVALUE123").run(context -> {
            String text = context.getBean(StripeProperties.class).toString();
            assertThat(text).doesNotContain("SUPERSECRETVALUE123").contains("<redacted>");
        });
    }

    @Test
    void theClientIsBuiltFromTheSettings() {
        StripeProperties properties = new StripeProperties(
                "sk_test_client",
                "http://localhost:12111/",
                Duration.ofSeconds(3),
                Duration.ofSeconds(9),
                2,
                new StripeProperties.CircuitBreakerProperties(20, 10, 50, Duration.ofSeconds(30), 3));

        StripeClient client = StripeConfiguration.newClient(properties);

        assertThat(client).isNotNull();
    }

    @Test
    void theCircuitBreakerStateIsPublishedAsAMetric() {
        runner.withPropertyValues("stripe.api-key=sk_test_metrics").run(context -> {
            MeterRegistry meters = context.getBean(MeterRegistry.class);
            assertThat(meters.find("resilience4j.circuitbreaker.state")
                            .tag("name", "stripe")
                            .gauges())
                    .isNotEmpty();
        });
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }
}
