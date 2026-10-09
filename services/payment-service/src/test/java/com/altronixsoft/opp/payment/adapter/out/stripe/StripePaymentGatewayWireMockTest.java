package com.altronixsoft.opp.payment.adapter.out.stripe;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.altronixsoft.opp.payment.application.CancelPaymentIntentRequest;
import com.altronixsoft.opp.payment.application.ConfirmPaymentIntentRequest;
import com.altronixsoft.opp.payment.application.CreatePaymentIntentRequest;
import com.altronixsoft.opp.payment.application.CreateRefundRequest;
import com.altronixsoft.opp.payment.application.GatewayErrorClass;
import com.altronixsoft.opp.payment.application.GatewayPaymentIntent;
import com.altronixsoft.opp.payment.application.GatewayRefund;
import com.altronixsoft.opp.payment.application.PaymentGateway;
import com.altronixsoft.opp.payment.application.PaymentGatewayException;
import com.altronixsoft.opp.payment.domain.Money;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;

/**
 * The gateway against a WireMock that plays Stripe, with the real SDK in between: what goes on the wire, how each kind of
 * failure is classified, what the circuit breaker does, and what is measured and logged. No Stripe account is needed.
 */
class StripePaymentGatewayWireMockTest {

    private static final String API_KEY = "sk_test_wiremockFAKE1234";
    private static final String CLIENT_SECRET = "pi_3PwmABCDEF_secret_ZyXwVuTsRqPo123456";
    private static final UUID PAYMENT_ID = UUID.fromString("0199e0a0-1111-7000-8000-000000000001");
    private static final UUID ORDER_ID = UUID.fromString("0199e0a0-2222-7000-8000-000000000002");
    private static final UUID REFUND_ID = UUID.fromString("0199e0a0-3333-7000-8000-000000000003");

    @RegisterExtension
    static WireMockExtension stripe = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private SimpleMeterRegistry meters;
    private ListAppender<ILoggingEvent> logs;
    private Logger gatewayLogger;

    @BeforeEach
    void captureLogs() {
        stripe.resetAll();
        meters = new SimpleMeterRegistry();
        gatewayLogger = (Logger) LoggerFactory.getLogger(StripePaymentGateway.class);
        gatewayLogger.setLevel(Level.DEBUG);
        logs = new ListAppender<>();
        logs.start();
        gatewayLogger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        gatewayLogger.detachAppender(logs);
    }

    // ---------------------------------------------------------------------------------------------- fixtures

    private PaymentGateway gateway(int maxNetworkRetries) {
        return gateway(maxNetworkRetries, Duration.ofMillis(800), defaultBreaker());
    }

    private static StripeProperties.CircuitBreakerProperties defaultBreaker() {
        return new StripeProperties.CircuitBreakerProperties(100, 100, 50, Duration.ofSeconds(30), 3);
    }

    private CircuitBreaker lastBreaker;

    private PaymentGateway gateway(
            int maxNetworkRetries, Duration readTimeout, StripeProperties.CircuitBreakerProperties breaker) {
        StripeProperties properties = new StripeProperties(
                API_KEY,
                stripe.baseUrl(),
                Duration.ofMillis(800),
                readTimeout,
                maxNetworkRetries,
                breaker,
                new StripeProperties.Webhook(java.util.List.of(), Duration.ofSeconds(300)));
        lastBreaker = StripeConfiguration.newCircuitBreaker(CircuitBreakerRegistry.ofDefaults(), breaker);
        return StripeConfiguration.newGateway(StripeConfiguration.newClient(properties), lastBreaker, meters);
    }

    private static String paymentIntentJson(String status) {
        return """
                {"id":"pi_3PwmABCDEF","object":"payment_intent","amount":3097,"currency":"eur","status":"%s",
                 "client_secret":"%s","created":1790000000,"livemode":false,
                 "metadata":{"orderId":"%s","paymentId":"%s"}}""".formatted(status, CLIENT_SECRET, ORDER_ID, PAYMENT_ID);
    }

    private static String declinedPaymentIntentJson() {
        return """
                {"id":"pi_3PwmABCDEF","object":"payment_intent","amount":3097,"currency":"eur",
                 "status":"requires_payment_method","client_secret":"%s","created":1790000000,"livemode":false,
                 "last_payment_error":{"type":"card_error","code":"card_declined","decline_code":"insufficient_funds",
                                       "message":"Your card has insufficient funds."}}""".formatted(CLIENT_SECRET);
    }

    private static String refundJson(String status) {
        return """
                {"id":"re_3PwmREFUND","object":"refund","amount":3097,"currency":"eur","status":"%s",
                 "payment_intent":"pi_3PwmABCDEF","created":1790000100,"failure_reason":null}""".formatted(status);
    }

    private static String errorJson(String type, String code, String message) {
        return """
                {"error":{"type":"%s","code":"%s","message":"%s"}}""".formatted(type, code, message);
    }

    private static CreatePaymentIntentRequest createRequest() {
        return new CreatePaymentIntentRequest(PAYMENT_ID, ORDER_ID, Money.of(3097, "EUR"));
    }

    private void stubCreate(int status, String body) {
        stripe.stubFor(post(urlPathEqualTo("/v1/payment_intents"))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Request-Id", "req_wiremock_1")
                        .withBody(body)));
    }

    private static Map<String, String> form(LoggedRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String pair : request.getBodyAsString().split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            String[] kv = pair.split("=", 2);
            fields.put(
                    URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(kv.length > 1 ? kv[1] : "", StandardCharsets.UTF_8));
        }
        return fields;
    }

    private List<LoggedRequest> requests() {
        return stripe.findAll(com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor(
                com.github.tomakehurst.wiremock.client.WireMock.anyUrl()));
    }

    private String allLogs() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + "\n" + b);
    }

    // ---------------------------------------------------------------------------------------------- the request

    @Nested
    class OnTheWire {

        @Test
        void createSendsTheParametersOfArchitectureSection8_2() {
            stubCreate(200, paymentIntentJson("requires_payment_method"));

            gateway(2).createPaymentIntent(createRequest());

            LoggedRequest sent = requests().getFirst();
            Map<String, String> params = form(sent);
            assertThat(params)
                    .containsEntry("amount", "3097")
                    .containsEntry("currency", "eur")
                    .containsEntry("automatic_payment_methods[enabled]", "true")
                    .containsEntry("automatic_payment_methods[allow_redirects]", "never")
                    .containsEntry("metadata[orderId]", ORDER_ID.toString())
                    .containsEntry("metadata[paymentId]", PAYMENT_ID.toString());
            assertThat(params.keySet())
                    .as("no payment_method_types: removed from the API in SDK 34, automatic methods are used")
                    .noneMatch(k -> k.startsWith("payment_method_types"))
                    .noneMatch(k -> k.equals("confirm"));
            assertThat(sent.getHeader("Authorization")).isEqualTo("Bearer " + API_KEY);
            assertThat(sent.getHeader("Stripe-Version")).matches("\\d{4}-\\d{2}-\\d{2}\\..+");
            assertThat(sent.getHeader("Content-Type")).startsWith("application/x-www-form-urlencoded");
        }

        @Test
        void createCarriesTheDeterministicIdempotencyKey() {
            stubCreate(200, paymentIntentJson("requires_payment_method"));
            PaymentGateway gateway = gateway(2);

            gateway.createPaymentIntent(createRequest());
            gateway.createPaymentIntent(createRequest());
            gateway.createPaymentIntent(
                    new CreatePaymentIntentRequest(UUID.randomUUID(), ORDER_ID, Money.of(100, "EUR")));

            List<String> keys =
                    requests().stream().map(r -> r.getHeader("Idempotency-Key")).toList();
            assertThat(keys.get(0)).isEqualTo("pi-create:" + PAYMENT_ID);
            assertThat(keys.get(1)).as("same payment, same key").isEqualTo(keys.get(0));
            assertThat(keys.get(2))
                    .as("another payment, another key")
                    .startsWith("pi-create:")
                    .isNotEqualTo(keys.get(0));
        }

        @Test
        void cancelSendsTheReasonAndItsOwnKey() {
            stripe.stubFor(post(urlPathEqualTo("/v1/payment_intents/pi_3PwmABCDEF/cancel"))
                    .willReturn(aResponse()
                            .withHeader("Content-Type", "application/json")
                            .withBody(paymentIntentJson("canceled"))));

            GatewayPaymentIntent canceled = gateway(2)
                    .cancelPaymentIntent(new CancelPaymentIntentRequest(
                            PAYMENT_ID, "pi_3PwmABCDEF", CancelPaymentIntentRequest.Reason.ABANDONED));

            stripe.verify(postRequestedFor(urlPathEqualTo("/v1/payment_intents/pi_3PwmABCDEF/cancel"))
                    .withHeader("Idempotency-Key", equalTo("pi-cancel:" + PAYMENT_ID)));
            assertThat(form(requests().getFirst())).containsEntry("cancellation_reason", "abandoned");
            assertThat(canceled.status()).isEqualTo("canceled");
        }

        @Test
        void refundIsForTheFullAmountWithItsOwnKeyAndMetadata() {
            stripe.stubFor(post(urlPathEqualTo("/v1/refunds"))
                    .willReturn(aResponse()
                            .withHeader("Content-Type", "application/json")
                            .withBody(refundJson("pending"))));

            GatewayRefund refund = gateway(2)
                    .createRefund(
                            new CreateRefundRequest(REFUND_ID, PAYMENT_ID, "pi_3PwmABCDEF", Money.of(3097, "EUR")));

            LoggedRequest sent = requests().getFirst();
            assertThat(sent.getHeader("Idempotency-Key")).isEqualTo("refund:" + REFUND_ID);
            assertThat(form(sent))
                    .containsEntry("payment_intent", "pi_3PwmABCDEF")
                    .containsEntry("amount", "3097")
                    .containsEntry("metadata[refundId]", REFUND_ID.toString())
                    .containsEntry("metadata[paymentId]", PAYMENT_ID.toString());
            assertThat(refund.id()).isEqualTo("re_3PwmREFUND");
            assertThat(refund.status()).isEqualTo("pending");
            assertThat(refund.amount()).isEqualTo(Money.of(3097, "EUR"));
            assertThat(refund.paymentIntentId()).isEqualTo("pi_3PwmABCDEF");
            assertThat(refund.failureReason()).isNull();
        }

        @Test
        void confirmUsesTheTestPaymentMethodAndAKeyPerAttempt() {
            stripe.stubFor(post(urlPathEqualTo("/v1/payment_intents/pi_3PwmABCDEF/confirm"))
                    .willReturn(aResponse()
                            .withHeader("Content-Type", "application/json")
                            .withBody(paymentIntentJson("succeeded"))));
            PaymentGateway gateway = gateway(2);
            UUID firstAttempt = UUID.randomUUID();

            gateway.confirmPaymentIntentForTest(
                    new ConfirmPaymentIntentRequest(PAYMENT_ID, "pi_3PwmABCDEF", "pm_card_visa", firstAttempt));
            gateway.confirmPaymentIntentForTest(
                    new ConfirmPaymentIntentRequest(PAYMENT_ID, "pi_3PwmABCDEF", "pm_card_visa", UUID.randomUUID()));

            List<LoggedRequest> sent = requests();
            assertThat(form(sent.getFirst())).containsEntry("payment_method", "pm_card_visa");
            assertThat(sent.get(0).getHeader("Idempotency-Key"))
                    .isEqualTo("pi-confirm:" + PAYMENT_ID + ":" + firstAttempt);
            assertThat(sent.get(1).getHeader("Idempotency-Key"))
                    .isNotEqualTo(sent.get(0).getHeader("Idempotency-Key"));
        }

        @Test
        void retrieveIsAPlainGetWithoutAnIdempotencyKey() {
            stripe.stubFor(get(urlPathEqualTo("/v1/payment_intents/pi_3PwmABCDEF"))
                    .willReturn(aResponse()
                            .withHeader("Content-Type", "application/json")
                            .withBody(paymentIntentJson("processing"))));

            GatewayPaymentIntent intent = gateway(2).retrievePaymentIntent("pi_3PwmABCDEF");

            stripe.verify(getRequestedFor(urlPathEqualTo("/v1/payment_intents/pi_3PwmABCDEF"))
                    .withoutHeader("Idempotency-Key")
                    .withHeader("Authorization", matching("Bearer sk_test_.*")));
            assertThat(intent.status()).isEqualTo("processing");
        }
    }

    // ---------------------------------------------------------------------------------------------- the answer

    @Nested
    class Mapping {

        @Test
        void aPaymentIntentIsMappedWithoutStripeTypes() {
            stubCreate(200, paymentIntentJson("requires_payment_method"));

            GatewayPaymentIntent intent = gateway(2).createPaymentIntent(createRequest());

            assertThat(intent.id()).isEqualTo("pi_3PwmABCDEF");
            assertThat(intent.status()).isEqualTo("requires_payment_method");
            assertThat(intent.amount()).isEqualTo(Money.of(3097, "EUR"));
            assertThat(intent.created()).isEqualTo(java.time.Instant.ofEpochSecond(1_790_000_000L));
            assertThat(intent.clientSecret()).isEqualTo(CLIENT_SECRET);
            assertThat(intent.lastErrorCode()).isNull();
        }

        @Test
        void theLastPaymentErrorIsMapped() {
            stripe.stubFor(get(urlPathEqualTo("/v1/payment_intents/pi_3PwmABCDEF"))
                    .willReturn(aResponse()
                            .withHeader("Content-Type", "application/json")
                            .withBody(declinedPaymentIntentJson())));

            GatewayPaymentIntent intent = gateway(2).retrievePaymentIntent("pi_3PwmABCDEF");

            assertThat(intent.lastErrorCode()).isEqualTo("card_declined");
            assertThat(intent.lastErrorDeclineCode()).isEqualTo("insufficient_funds");
            assertThat(intent.lastErrorMessage()).isEqualTo("Your card has insufficient funds.");
        }

        @Test
        void theClientSecretNeverAppearsInToString() {
            stubCreate(200, paymentIntentJson("requires_payment_method"));

            GatewayPaymentIntent intent = gateway(2).createPaymentIntent(createRequest());

            assertThat(intent.toString())
                    .doesNotContain(CLIENT_SECRET)
                    .doesNotContain("_secret_")
                    .contains("<redacted>");
        }

        @Test
        void manualCaptureIsPassedOnSoTheDomainCanRejectIt() {
            stubCreate(200, paymentIntentJson("requires_capture"));

            assertThat(gateway(2).createPaymentIntent(createRequest()).status()).isEqualTo("requires_capture");
        }
    }

    // ---------------------------------------------------------------------------------------------- failures

    @Nested
    class Failures {

        private PaymentGatewayException failureOf(PaymentGateway gateway) {
            return org.assertj.core.api.Assertions.catchThrowableOfType(
                    PaymentGatewayException.class, () -> gateway.createPaymentIntent(createRequest()));
        }

        @Test
        void a500IsTransientAndRetriedByTheSdkWithTheSameKey() {
            stubCreate(500, errorJson("api_error", "internal", "Something went wrong"));

            PaymentGatewayException failure = failureOf(gateway(2));

            assertThat(failure.errorClass()).isEqualTo(GatewayErrorClass.TRANSIENT);
            assertThat(failure.httpStatus()).isEqualTo(500);
            assertThat(failure.providerRequestId()).isEqualTo("req_wiremock_1");
            assertThat(requests())
                    .as("one attempt and maxNetworkRetries=2 retries")
                    .hasSize(3);
            assertThat(requests().stream()
                            .map(r -> r.getHeader("Idempotency-Key"))
                            .distinct())
                    .as("every retry is the same request to Stripe")
                    .containsExactly("pi-create:" + PAYMENT_ID);
        }

        @Test
        void aRetryThatSucceedsReturnsTheResultAndNeverCreatesASecondIntent() {
            stripe.stubFor(post(urlPathEqualTo("/v1/payment_intents"))
                    .inScenario("flaky")
                    .whenScenarioStateIs(Scenario.STARTED)
                    .willReturn(aResponse()
                            .withStatus(503)
                            .withHeader("Content-Type", "application/json")
                            .withBody(errorJson("api_error", "unavailable", "try later")))
                    .willSetStateTo("recovered"));
            stripe.stubFor(post(urlPathEqualTo("/v1/payment_intents"))
                    .inScenario("flaky")
                    .whenScenarioStateIs("recovered")
                    .willReturn(aResponse()
                            .withHeader("Content-Type", "application/json")
                            .withBody(paymentIntentJson("requires_payment_method"))));

            GatewayPaymentIntent intent = gateway(2).createPaymentIntent(createRequest());

            assertThat(intent.id()).isEqualTo("pi_3PwmABCDEF");
            assertThat(requests().stream()
                            .map(r -> r.getHeader("Idempotency-Key"))
                            .distinct())
                    .hasSize(1);
        }

        @Test
        void a429IsTransientAndNotRetriedByTheSdk() {
            stubCreate(429, errorJson("invalid_request_error", "rate_limit", "Too many requests"));

            PaymentGatewayException failure = failureOf(gateway(2));

            assertThat(failure.errorClass()).isEqualTo(GatewayErrorClass.TRANSIENT);
            assertThat(failure.httpStatus()).isEqualTo(429);
            assertThat(requests())
                    .as("the Java SDK does not retry 429; the work queue's backoff does")
                    .hasSize(1);
        }

        @Test
        void a409WhileTheKeyIsInUseIsTransient() {
            stubCreate(
                    409, errorJson("invalid_request_error", "idempotency_key_in_use", "Request already in progress"));

            PaymentGatewayException failure = failureOf(gateway(0));

            assertThat(failure.errorClass()).isEqualTo(GatewayErrorClass.TRANSIENT);
            assertThat(failure.httpStatus()).isEqualTo(409);
        }

        @Test
        void a400IsPermanentAndNotRetried() {
            stubCreate(400, errorJson("invalid_request_error", "parameter_invalid_integer", "Invalid integer: abc"));

            PaymentGatewayException failure = failureOf(gateway(2));

            assertThat(failure.errorClass()).isEqualTo(GatewayErrorClass.PERMANENT);
            assertThat(failure.code()).isEqualTo("parameter_invalid_integer");
            assertThat(requests()).hasSize(1);
        }

        @Test
        void aDeclinedCardIsPermanentAndKeepsTheDeclineCode() {
            stubCreate(402, """
                    {"error":{"type":"card_error","code":"card_declined","decline_code":"generic_decline","message":"Your card was declined."}}""");

            PaymentGatewayException failure = failureOf(gateway(2));

            assertThat(failure.errorClass()).isEqualTo(GatewayErrorClass.PERMANENT);
            assertThat(failure.code()).isEqualTo("card_declined");
            assertThat(failure.declineCode()).isEqualTo("generic_decline");
        }

        @ParameterizedTest
        @CsvSource({"401, api_key_invalid", "403, permission_denied"})
        void badCredentialsAreAConfigurationProblemNotRetriedBlindly(int status, String code) {
            stubCreate(status, errorJson("invalid_request_error", code, "Invalid API Key provided: " + API_KEY));

            PaymentGatewayException failure = failureOf(gateway(2));

            assertThat(failure.errorClass()).isEqualTo(GatewayErrorClass.CONFIG);
            assertThat(requests()).hasSize(1);
            assertThat(failure.getMessage()).doesNotContain(API_KEY).contains("sk_test_***");
        }

        @Test
        void aReusedKeyWithOtherParametersIsAnIdempotencyMismatch() {
            stubCreate(
                    400,
                    errorJson(
                            "idempotency_error",
                            "idempotency_key_reused",
                            "Keys for idempotent requests can only be used with the same parameters they were first used with."));

            PaymentGatewayException failure = failureOf(gateway(2));

            assertThat(failure.errorClass()).isEqualTo(GatewayErrorClass.IDEMPOTENCY_MISMATCH);
            assertThat(requests()).hasSize(1);
        }

        @Test
        void aReadTimeoutIsTransient() {
            stripe.stubFor(post(urlPathEqualTo("/v1/payment_intents"))
                    .willReturn(
                            aResponse().withFixedDelay(2000).withBody(paymentIntentJson("requires_payment_method"))));

            PaymentGatewayException failure = failureOf(gateway(0, Duration.ofMillis(300), defaultBreaker()));

            assertThat(failure.errorClass()).isEqualTo(GatewayErrorClass.TRANSIENT);
            assertThat(failure.httpStatus()).isNull();
            assertThat(failure.getCause()).isInstanceOf(com.stripe.exception.ApiConnectionException.class);
        }

        @Test
        void aReadTimeoutIsRetriedWithTheSameKeyUpToMaxNetworkRetries() {
            stripe.stubFor(post(urlPathEqualTo("/v1/payment_intents"))
                    .willReturn(
                            aResponse().withFixedDelay(1500).withBody(paymentIntentJson("requires_payment_method"))));

            PaymentGatewayException failure = failureOf(gateway(2, Duration.ofMillis(250), defaultBreaker()));

            assertThat(failure.errorClass()).isEqualTo(GatewayErrorClass.TRANSIENT);
            assertThat(requests()).hasSize(3);
            assertThat(requests().stream()
                            .map(r -> r.getHeader("Idempotency-Key"))
                            .distinct())
                    .hasSize(1);
        }

        @ParameterizedTest
        @CsvSource({"CONNECTION_RESET_BY_PEER", "EMPTY_RESPONSE", "MALFORMED_RESPONSE_CHUNK", "RANDOM_DATA_THEN_CLOSE"})
        void aBrokenConnectionIsTransient(Fault fault) {
            stripe.stubFor(post(urlPathEqualTo("/v1/payment_intents"))
                    .willReturn(aResponse().withFault(fault)));

            assertThat(failureOf(gateway(0)).errorClass()).isEqualTo(GatewayErrorClass.TRANSIENT);
        }

        @Test
        void aRefusedConnectionIsTransient() throws Exception {
            int closedPort;
            try (ServerSocket socket = new ServerSocket(0)) {
                closedPort = socket.getLocalPort();
            }
            StripeProperties properties = new StripeProperties(
                    API_KEY,
                    "http://localhost:" + closedPort,
                    Duration.ofMillis(500),
                    Duration.ofMillis(500),
                    0,
                    defaultBreaker(),
                    new StripeProperties.Webhook(java.util.List.of(), Duration.ofSeconds(300)));
            PaymentGateway gateway = StripeConfiguration.newGateway(
                    StripeConfiguration.newClient(properties),
                    StripeConfiguration.newCircuitBreaker(CircuitBreakerRegistry.ofDefaults(), defaultBreaker()),
                    meters);

            assertThat(failureOf(gateway).errorClass()).isEqualTo(GatewayErrorClass.TRANSIENT);
        }

        @Test
        void aBodyThatIsNotJsonIsTransient() {
            stubCreate(200, "<html>bad gateway page</html>");

            assertThat(failureOf(gateway(0)).errorClass()).isEqualTo(GatewayErrorClass.TRANSIENT);
        }

        @Test
        void anHtmlErrorPageFromAProxyIsTransient() {
            stubCreate(502, "<html>Bad Gateway</html>");

            PaymentGatewayException failure = failureOf(gateway(0));

            assertThat(failure.errorClass()).isEqualTo(GatewayErrorClass.TRANSIENT);
            assertThat(failure.httpStatus()).isEqualTo(502);
        }
    }

    // ---------------------------------------------------------------------------------------------- circuit breaker

    @Nested
    class CircuitBreaking {

        private final StripeProperties.CircuitBreakerProperties small =
                new StripeProperties.CircuitBreakerProperties(4, 4, 50, Duration.ofMillis(400), 2);

        @Test
        void opensAfterEnoughTransientFailuresAndThenStopsCallingStripe() {
            stubCreate(503, errorJson("api_error", "unavailable", "down"));
            PaymentGateway gateway = gateway(0, Duration.ofMillis(800), small);

            for (int i = 0; i < 4; i++) {
                assertThatThrownBy(() -> gateway.createPaymentIntent(createRequest()))
                        .isInstanceOfSatisfying(
                                PaymentGatewayException.class,
                                e -> assertThat(e.circuitOpen()).isFalse());
            }
            assertThat(lastBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
            int callsBefore = requests().size();

            assertThatThrownBy(() -> gateway.createPaymentIntent(createRequest()))
                    .isInstanceOfSatisfying(PaymentGatewayException.class, e -> {
                        assertThat(e.circuitOpen()).isTrue();
                        assertThat(e.errorClass())
                                .as("callers back off and retry later")
                                .isEqualTo(GatewayErrorClass.TRANSIENT);
                        assertThat(e.code()).isEqualTo("circuit_open");
                    });

            assertThat(requests())
                    .as("an open breaker does not call Stripe")
                    .hasSize(callsBefore)
                    .hasSize(4);
            assertThat(meters.get("stripe.api.errors")
                            .tag("type", "circuit_open")
                            .counter()
                            .count())
                    .isEqualTo(1);
            assertThat(meters.get("stripe.api.errors")
                            .tag("type", "transient")
                            .counter()
                            .count())
                    .isEqualTo(4);
        }

        @Test
        void rejectedRequestsAndDeclinedCardsDoNotCountBecauseStripeIsHealthy() {
            stubCreate(400, errorJson("invalid_request_error", "parameter_invalid_integer", "bad"));
            PaymentGateway gateway = gateway(0, Duration.ofMillis(800), small);

            for (int i = 0; i < 12; i++) {
                assertThatThrownBy(() -> gateway.createPaymentIntent(createRequest()))
                        .isInstanceOfSatisfying(
                                PaymentGatewayException.class,
                                e -> assertThat(e.errorClass()).isEqualTo(GatewayErrorClass.PERMANENT));
            }

            assertThat(lastBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(requests()).hasSize(12);
        }

        @Test
        void badCredentialsAreNotAnOutageEither() {
            stubCreate(401, errorJson("invalid_request_error", "api_key_invalid", "bad key"));
            PaymentGateway gateway = gateway(0, Duration.ofMillis(800), small);

            for (int i = 0; i < 8; i++) {
                assertThatThrownBy(() -> gateway.createPaymentIntent(createRequest()))
                        .isInstanceOfSatisfying(
                                PaymentGatewayException.class,
                                e -> assertThat(e.errorClass()).isEqualTo(GatewayErrorClass.CONFIG));
            }

            assertThat(lastBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @Test
        void aMixOfHealthyAndTransientCallsStaysClosedBelowTheThreshold() {
            stripe.stubFor(post(urlPathEqualTo("/v1/payment_intents"))
                    .inScenario("mix")
                    .whenScenarioStateIs(Scenario.STARTED)
                    .willReturn(aResponse().withStatus(503).withBody(errorJson("api_error", "x", "down")))
                    .willSetStateTo("ok1"));
            stripe.stubFor(post(urlPathEqualTo("/v1/payment_intents"))
                    .inScenario("mix")
                    .whenScenarioStateIs("ok1")
                    .willReturn(aResponse()
                            .withHeader("Content-Type", "application/json")
                            .withBody(paymentIntentJson("requires_payment_method"))));
            PaymentGateway gateway = gateway(0, Duration.ofMillis(800), small);

            assertThatThrownBy(() -> gateway.createPaymentIntent(createRequest()))
                    .isInstanceOf(PaymentGatewayException.class);
            for (int i = 0; i < 3; i++) {
                gateway.createPaymentIntent(createRequest());
            }

            assertThat(lastBreaker.getState()).as("1 failure in 4 calls = 25 %").isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @Test
        void aRecoveredStripeClosesTheBreakerAgainAfterTheWaitAndSuccessfulTrials() throws Exception {
            stubCreate(503, errorJson("api_error", "unavailable", "down"));
            PaymentGateway gateway = gateway(0, Duration.ofMillis(800), small);
            for (int i = 0; i < 4; i++) {
                assertThatThrownBy(() -> gateway.createPaymentIntent(createRequest()))
                        .isInstanceOf(PaymentGatewayException.class);
            }
            assertThat(lastBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

            stripe.resetAll();
            stubCreate(200, paymentIntentJson("requires_payment_method"));
            Thread.sleep(500);

            gateway.createPaymentIntent(createRequest());
            assertThat(lastBreaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
            gateway.createPaymentIntent(createRequest());

            assertThat(lastBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @Test
        void aFailedTrialOpensItAgain() throws Exception {
            stubCreate(503, errorJson("api_error", "unavailable", "down"));
            PaymentGateway gateway = gateway(0, Duration.ofMillis(800), small);
            for (int i = 0; i < 4; i++) {
                assertThatThrownBy(() -> gateway.createPaymentIntent(createRequest()))
                        .isInstanceOf(PaymentGatewayException.class);
            }
            Thread.sleep(500);

            assertThatThrownBy(() -> gateway.createPaymentIntent(createRequest()))
                    .isInstanceOfSatisfying(
                            PaymentGatewayException.class,
                            e -> assertThat(e.circuitOpen()).isFalse());
            assertThatThrownBy(() -> gateway.createPaymentIntent(createRequest()))
                    .isInstanceOfSatisfying(
                            PaymentGatewayException.class,
                            e -> assertThat(e.circuitOpen()).isFalse());

            assertThat(lastBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        }

        @Test
        void oneBreakerGuardsEveryOperation() {
            stubCreate(503, errorJson("api_error", "unavailable", "down"));
            PaymentGateway gateway = gateway(0, Duration.ofMillis(800), small);
            for (int i = 0; i < 4; i++) {
                assertThatThrownBy(() -> gateway.createPaymentIntent(createRequest()))
                        .isInstanceOf(PaymentGatewayException.class);
            }

            assertThatThrownBy(() -> gateway.retrievePaymentIntent("pi_x"))
                    .isInstanceOfSatisfying(
                            PaymentGatewayException.class,
                            e -> assertThat(e.circuitOpen()).isTrue());
            assertThatThrownBy(() -> gateway.createRefund(
                            new CreateRefundRequest(REFUND_ID, PAYMENT_ID, "pi_x", Money.of(1, "EUR"))))
                    .isInstanceOfSatisfying(
                            PaymentGatewayException.class,
                            e -> assertThat(e.circuitOpen()).isTrue());
        }
    }

    // ---------------------------------------------------------------------------------------------- observability

    @Nested
    class Observability {

        @Test
        void aSuccessfulCallIsTimedByOperationAndOutcome() {
            stubCreate(200, paymentIntentJson("requires_payment_method"));

            gateway(2).createPaymentIntent(createRequest());

            var timer = meters.get("stripe.api.latency")
                    .tag("operation", "create_payment_intent")
                    .tag("outcome", "success")
                    .timer();
            assertThat(timer.count()).isEqualTo(1);
            assertThat(timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS))
                    .isPositive();
            assertThat(meters.find("stripe.api.errors").counters()).isEmpty();
        }

        @ParameterizedTest
        @CsvSource({
            "503, api_error, transient",
            "429, rate_limit, transient",
            "400, parameter_invalid_integer, permanent",
            "401, api_key_invalid, config",
            "400, idempotency_error_code, idempotency_mismatch"
        })
        void aFailureIsTimedAndCountedByItsClassification(int status, String code, String type) {
            String errorType = type.equals("idempotency_mismatch") ? "idempotency_error" : "invalid_request_error";
            stubCreate(status, errorJson(errorType, code, "failed"));

            assertThatThrownBy(() -> gateway(0).createPaymentIntent(createRequest()))
                    .isInstanceOf(PaymentGatewayException.class);

            assertThat(meters.get("stripe.api.errors")
                            .tag("type", type)
                            .counter()
                            .count())
                    .isEqualTo(1);
            assertThat(meters.get("stripe.api.latency")
                            .tag("operation", "create_payment_intent")
                            .tag("outcome", type)
                            .timer()
                            .count())
                    .isEqualTo(1);
        }

        @Test
        void everyOperationHasItsOwnLatencySeries() {
            stubCreate(200, paymentIntentJson("requires_payment_method"));
            stripe.stubFor(get(urlPathEqualTo("/v1/payment_intents/pi_3PwmABCDEF"))
                    .willReturn(aResponse().withBody(paymentIntentJson("requires_payment_method"))));
            stripe.stubFor(post(urlPathEqualTo("/v1/payment_intents/pi_3PwmABCDEF/cancel"))
                    .willReturn(aResponse().withBody(paymentIntentJson("canceled"))));
            stripe.stubFor(post(urlPathEqualTo("/v1/payment_intents/pi_3PwmABCDEF/confirm"))
                    .willReturn(aResponse().withBody(paymentIntentJson("succeeded"))));
            stripe.stubFor(
                    post(urlPathEqualTo("/v1/refunds")).willReturn(aResponse().withBody(refundJson("succeeded"))));
            PaymentGateway gateway = gateway(0);

            gateway.createPaymentIntent(createRequest());
            gateway.retrievePaymentIntent("pi_3PwmABCDEF");
            gateway.cancelPaymentIntent(new CancelPaymentIntentRequest(
                    PAYMENT_ID, "pi_3PwmABCDEF", CancelPaymentIntentRequest.Reason.REQUESTED_BY_CUSTOMER));
            gateway.confirmPaymentIntentForTest(
                    new ConfirmPaymentIntentRequest(PAYMENT_ID, "pi_3PwmABCDEF", "pm_card_visa", UUID.randomUUID()));
            gateway.createRefund(
                    new CreateRefundRequest(REFUND_ID, PAYMENT_ID, "pi_3PwmABCDEF", Money.of(3097, "EUR")));

            assertThat(meters.find("stripe.api.latency").timers())
                    .extracting(t -> t.getId().getTag("operation"))
                    .containsExactlyInAnyOrder(
                            "create_payment_intent",
                            "retrieve_payment_intent",
                            "cancel_payment_intent",
                            "confirm_payment_intent",
                            "create_refund");
        }

        @Test
        void theLogCarriesStripesRequestIdButNoSecrets() {
            stubCreate(200, paymentIntentJson("requires_payment_method"));
            gateway(2).createPaymentIntent(createRequest());
            stubCreate(
                    500,
                    errorJson(
                            "api_error", "internal", "Invalid API Key provided: " + API_KEY + " for " + CLIENT_SECRET));
            assertThatThrownBy(() -> gateway(0).createPaymentIntent(createRequest()))
                    .isInstanceOf(PaymentGatewayException.class);
            stripe.resetAll();
            stubCreate(
                    401, errorJson("invalid_request_error", "api_key_invalid", "Invalid API Key provided: " + API_KEY));
            assertThatThrownBy(() -> gateway(0).createPaymentIntent(createRequest()))
                    .isInstanceOf(PaymentGatewayException.class);

            String log = allLogs();
            assertThat(log).contains("req_wiremock_1").contains("create_payment_intent");
            assertThat(log).as("no API key").doesNotContain(API_KEY).doesNotContain("FAKEKEYVALUE1234");
            assertThat(log).as("no client secret").doesNotContain(CLIENT_SECRET).doesNotContain("ZyXwVuTsRqPo123456");
            assertThat(logs.list).extracting(ILoggingEvent::getLevel).contains(Level.DEBUG, Level.WARN, Level.ERROR);
            assertThat(logs.list)
                    .as("no event carries the raw exception, whose message could hold the response")
                    .allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
        }

        @Test
        void theExceptionThatReachesTheCallerIsScrubbedToo() {
            stubCreate(500, errorJson("api_error", "internal", "oops " + CLIENT_SECRET + " and " + API_KEY));

            PaymentGatewayException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                    PaymentGatewayException.class, () -> gateway(0).createPaymentIntent(createRequest()));

            assertThat(failure.getMessage()).doesNotContain(CLIENT_SECRET).doesNotContain(API_KEY);
        }
    }
}
