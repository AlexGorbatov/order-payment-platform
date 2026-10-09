package com.altronixsoft.opp.payment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ScenarioMappingBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A WireMock that plays the Stripe API for the integration tests, shared by the JVM; the service talks to it through the
 * real Stripe SDK ({@code stripe.api-base}). No Stripe account and no network are needed.
 */
final class TestStripe {

    /** The (fake, test-mode shaped) client secret that every stubbed PaymentIntent carries. */
    static final String CLIENT_SECRET = "pi_3ItStub_secret_nEvErLeAkMe0123456789";

    static final WireMockServer SERVER =
            new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

    static {
        SERVER.start();
    }

    private TestStripe() {}

    static String baseUrl() {
        return SERVER.baseUrl();
    }

    static void reset() {
        SERVER.resetAll();
    }

    // ---- bodies ----

    static String paymentIntent(String id, String status, long amount, String currency) {
        return """
                {"id":"%s","object":"payment_intent","amount":%d,"currency":"%s","status":"%s",
                 "client_secret":"%s","created":1790000000,"livemode":false}""".formatted(id, amount, currency.toLowerCase(java.util.Locale.ROOT), status, CLIENT_SECRET);
    }

    static String error(String type, String code, String message) {
        return """
                {"error":{"type":"%s","code":"%s","message":"%s"}}""".formatted(type, code, message);
    }

    // ---- stubs ----

    private static MappingBuilder json(MappingBuilder builder, int status, String body) {
        return builder.willReturn(aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withHeader("Request-Id", "req_it_" + status)
                .withBody(body));
    }

    /** Every {@code POST /v1/payment_intents} answers 200 with a PaymentIntent of the posted amount. */
    static void stubCreate(String paymentIntentId, long amount, String currency) {
        SERVER.stubFor(json(
                post(urlPathEqualTo("/v1/payment_intents")),
                200,
                paymentIntent(paymentIntentId, "requires_payment_method", amount, currency)));
    }

    /** {@code POST /v1/payment_intents} answers the given responses in order; the last one repeats. */
    static void stubCreateSequence(StubbedReply... replies) {
        for (int i = 0; i < replies.length; i++) {
            String state = i == 0 ? Scenario.STARTED : "step-" + i;
            ScenarioMappingBuilder stub = post(urlPathEqualTo("/v1/payment_intents"))
                    .inScenario("create")
                    .whenScenarioStateIs(state);
            if (i < replies.length - 1) {
                stub = stub.willSetStateTo("step-" + (i + 1));
            }
            SERVER.stubFor(json(stub, replies[i].status(), replies[i].body()));
        }
    }

    /** {@code GET /v1/payment_intents/{id}} answers 200 with a PaymentIntent in {@code status}. */
    static void stubRetrieve(String paymentIntentId, String status, long amount, String currency) {
        SERVER.stubFor(json(
                get(urlPathEqualTo("/v1/payment_intents/" + paymentIntentId)),
                200,
                paymentIntent(paymentIntentId, status, amount, currency)));
    }

    /** Like {@link #stubRetrieve}, answering only after {@code delay}: a slow Stripe while something else happens. */
    static void stubRetrieveSlowly(String paymentIntentId, String status, java.time.Duration delay) {
        SERVER.stubFor(get(urlPathEqualTo("/v1/payment_intents/" + paymentIntentId))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay((int) delay.toMillis())
                        .withBody(paymentIntent(paymentIntentId, status, 3097, "EUR"))));
    }

    static void stubRetrieveFailing(int status) {
        SERVER.stubFor(json(
                get(urlPathMatching("/v1/payment_intents/.+")),
                status,
                error("api_error", "api_error", "Stripe is having a bad day")));
    }

    /** {@code POST /v1/payment_intents/{id}/confirm} answers 200 with a PaymentIntent in {@code status}. */
    static void stubConfirm(String paymentIntentId, String status, long amount, String currency) {
        SERVER.stubFor(json(
                post(urlPathEqualTo("/v1/payment_intents/" + paymentIntentId + "/confirm")),
                200,
                paymentIntent(paymentIntentId, status, amount, currency)));
    }

    static void stubConfirmDeclined(String paymentIntentId) {
        SERVER.stubFor(json(post(urlPathEqualTo("/v1/payment_intents/" + paymentIntentId + "/confirm")), 402, """
                {"error":{"type":"card_error","code":"card_declined","decline_code":"generic_decline",
                 "message":"Your card was declined."}}"""));
    }

    /** {@code POST /v1/payment_intents/{id}/cancel} answers 200 with the PaymentIntent {@code canceled}. */
    static void stubCancel(String paymentIntentId) {
        SERVER.stubFor(json(
                post(urlPathEqualTo("/v1/payment_intents/" + paymentIntentId + "/cancel")),
                200,
                paymentIntent(paymentIntentId, "canceled", 3097, "EUR")));
    }

    /** The cancellation comes too late: the PaymentIntent already succeeded (F19). */
    static void stubCancelUnexpectedState(String paymentIntentId) {
        SERVER.stubFor(json(
                post(urlPathEqualTo("/v1/payment_intents/" + paymentIntentId + "/cancel")),
                400,
                error(
                        "invalid_request_error",
                        "payment_intent_unexpected_state",
                        "You cannot cancel this PaymentIntent because it has a status of succeeded.")));
    }

    /** {@code POST /v1/refunds} answers 200 with a refund {@code refundId} in {@code status}. */
    static void stubRefund(String refundId, String paymentIntentId, String status) {
        SERVER.stubFor(
                json(post(urlPathEqualTo("/v1/refunds")), 200, """
                {"id":"%s","object":"refund","amount":3097,"currency":"eur","status":"%s","payment_intent":"%s",
                 "failure_reason":null,"created":1790000000,"metadata":{}}""".formatted(refundId, status, paymentIntentId)));
    }

    // ---- what the service sent ----

    record StubbedReply(int status, String body) {

        static StubbedReply ok(String body) {
            return new StubbedReply(200, body);
        }

        static StubbedReply status(int status, String type, String code) {
            return new StubbedReply(status, error(type, code, "stubbed " + code));
        }
    }

    static List<LoggedRequest> requests() {
        return SERVER.findAll(anyRequestedFor(anyUrl()));
    }

    static List<LoggedRequest> creates() {
        return requests().stream()
                .filter(r -> r.getMethod().getName().equals("POST"))
                .filter(r -> r.getUrl().equals("/v1/payment_intents"))
                .toList();
    }

    static List<LoggedRequest> cancels() {
        return requests().stream()
                .filter(r -> r.getMethod().getName().equals("POST"))
                .filter(r -> r.getUrl().endsWith("/cancel"))
                .toList();
    }

    static List<LoggedRequest> refunds() {
        return requests().stream()
                .filter(r -> r.getMethod().getName().equals("POST"))
                .filter(r -> r.getUrl().equals("/v1/refunds"))
                .toList();
    }

    static Map<String, String> form(LoggedRequest request) {
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
}
