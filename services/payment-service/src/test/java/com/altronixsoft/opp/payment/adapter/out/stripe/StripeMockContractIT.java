package com.altronixsoft.opp.payment.adapter.out.stripe;

import static org.assertj.core.api.Assertions.assertThat;

import com.altronixsoft.opp.payment.application.CancelPaymentIntentRequest;
import com.altronixsoft.opp.payment.application.ConfirmPaymentIntentRequest;
import com.altronixsoft.opp.payment.application.CreatePaymentIntentRequest;
import com.altronixsoft.opp.payment.application.CreateRefundRequest;
import com.altronixsoft.opp.payment.application.GatewayPaymentIntent;
import com.altronixsoft.opp.payment.application.GatewayRefund;
import com.altronixsoft.opp.payment.application.PaymentGateway;
import com.altronixsoft.opp.payment.domain.Money;
import com.altronixsoft.opp.payment.domain.PaymentStatus;
import com.altronixsoft.opp.payment.domain.StripePaymentIntentStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Contract smoke test against {@code stripe/stripe-mock} (ADR-0012): the real SDK talks to Stripe's OpenAPI-driven mock,
 * which validates every request against Stripe's published spec. If the parameters the gateway sends drift from the API
 * (a removed or renamed field after an SDK upgrade), this test fails before a real account would. stripe-mock is
 * stateless and answers with fixtures, so the assertions are about shape and mapping, not about business values.
 */
class StripeMockContractIT {

    /** The same image the local environment uses (infra/docker-compose.yml). */
    private static final GenericContainer<?> STRIPE_MOCK = new GenericContainer<>(
                    DockerImageName.parse("stripe/stripe-mock:v0.205.0"))
            .withExposedPorts(12111)
            .waitingFor(Wait.forListeningPort());

    private static PaymentGateway gateway;

    @BeforeAll
    static void connect() {
        STRIPE_MOCK.start();
        StripeProperties properties = new StripeProperties(
                "sk_test_contract",
                "http://" + STRIPE_MOCK.getHost() + ":" + STRIPE_MOCK.getMappedPort(12111),
                Duration.ofSeconds(5),
                Duration.ofSeconds(15),
                2,
                new StripeProperties.CircuitBreakerProperties(20, 10, 50, Duration.ofSeconds(30), 3),
                new StripeProperties.Webhook(java.util.List.of(), Duration.ofSeconds(300)));
        gateway = StripeConfiguration.newGateway(
                StripeConfiguration.newClient(properties),
                StripeConfiguration.newCircuitBreaker(CircuitBreakerRegistry.ofDefaults(), properties.circuitBreaker()),
                new SimpleMeterRegistry());
    }

    @AfterAll
    static void stop() {
        STRIPE_MOCK.stop();
    }

    private static void assertIsAPaymentIntent(GatewayPaymentIntent intent) {
        assertThat(intent.id()).startsWith("pi_");
        assertThat(intent.status()).isNotBlank();
        assertThat(StripePaymentIntentStatus.toPaymentStatus(intent.status()))
                .as("the status Stripe reports maps to a payment status the domain knows")
                .isNotNull();
        assertThat(intent.amount()).isNotNull();
        assertThat(intent.amount().currencyCode()).hasSize(3);
        assertThat(intent.created()).isNotNull();
    }

    @Test
    void createMapsAPaymentIntent() {
        GatewayPaymentIntent intent = gateway.createPaymentIntent(
                new CreatePaymentIntentRequest(UUID.randomUUID(), UUID.randomUUID(), Money.of(3097, "EUR")));

        assertIsAPaymentIntent(intent);
        assertThat(intent.status()).isEqualTo("requires_payment_method");
        assertThat(StripePaymentIntentStatus.toPaymentStatus(intent.status()))
                .isEqualTo(PaymentStatus.REQUIRES_PAYMENT_METHOD);
        assertThat(intent.amount()).isEqualTo(Money.of(3097, "EUR"));
        assertThat(intent.clientSecret()).contains("_secret_");
    }

    @Test
    void retrieveMapsAPaymentIntent() {
        GatewayPaymentIntent intent = gateway.retrievePaymentIntent("pi_123");

        assertIsAPaymentIntent(intent);
        assertThat(intent.clientSecret()).isNotBlank();
    }

    @Test
    void cancelMapsAPaymentIntent() {
        GatewayPaymentIntent intent = gateway.cancelPaymentIntent(new CancelPaymentIntentRequest(
                UUID.randomUUID(), "pi_123", CancelPaymentIntentRequest.Reason.REQUESTED_BY_CUSTOMER));

        assertIsAPaymentIntent(intent);
    }

    @Test
    void cancelAcceptsTheOtherReasonToo() {
        assertIsAPaymentIntent(gateway.cancelPaymentIntent(new CancelPaymentIntentRequest(
                UUID.randomUUID(), "pi_123", CancelPaymentIntentRequest.Reason.ABANDONED)));
    }

    @Test
    void refundMapsARefund() {
        GatewayRefund refund = gateway.createRefund(new CreateRefundRequest(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "pi_123", Money.of(3097, "EUR")));

        assertThat(refund.id()).startsWith("re_");
        assertThat(refund.status()).isIn("pending", "succeeded", "failed", "requires_action", "canceled");
        assertThat(refund.amount()).isNotNull();
        assertThat(refund.amount().currencyCode()).hasSize(3);
        assertThat(refund.paymentIntentId()).startsWith("pi_");
        assertThat(refund.created()).isNotNull();
    }

    @Test
    void confirmForTestMapsAPaymentIntent() {
        GatewayPaymentIntent intent = gateway.confirmPaymentIntentForTest(
                new ConfirmPaymentIntentRequest(UUID.randomUUID(), "pi_123", "pm_card_visa", UUID.randomUUID()));

        assertIsAPaymentIntent(intent);
    }

    @Test
    void theSameCreateRequestCanBeRepeatedSafely() {
        CreatePaymentIntentRequest request =
                new CreatePaymentIntentRequest(UUID.randomUUID(), UUID.randomUUID(), Money.of(500, "EUR"));

        GatewayPaymentIntent first = gateway.createPaymentIntent(request);
        GatewayPaymentIntent second = gateway.createPaymentIntent(request);

        assertIsAPaymentIntent(first);
        assertIsAPaymentIntent(second);
    }
}
