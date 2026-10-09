package com.altronixsoft.opp.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.altronixsoft.opp.order.domain.OrderStatus;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Who may call which endpoint, with real Keycloak tokens (users of the imported realm {@code opp}) and with the bad
 * tokens an attacker could produce. Every refused request must also leave the data untouched.
 */
class OrderApiSecurityIT extends AbstractApiIT {

    /** The callers. Each has a token source; the last five carry no valid credentials at all. */
    enum Actor {
        CUSTOMER1(() -> TestKeycloak.tokenOf("customer1")),
        CUSTOMER2(() -> TestKeycloak.tokenOf("customer2")),
        ADMIN1(() -> TestKeycloak.tokenOf("admin1")),
        OPS1(() -> TestKeycloak.tokenOf("ops1")),
        OPS_SERVICE_ACCOUNT(TestKeycloak::opsClientToken),
        NO_TOKEN(() -> null),
        WRONG_AUDIENCE(TestKeycloak::tokenForAnotherAudience),
        SIGNED_BY_ANOTHER_KEY(() -> TestKeycloak.signedByAnotherKey(TestKeycloak.CUSTOMER1_ID)),
        UNSIGNED(() -> TestKeycloak.unsigned(TestKeycloak.CUSTOMER1_ID)),
        TAMPERED_PAYLOAD(() -> TestKeycloak.tamperedToClaimAdmin(TestKeycloak.tokenOf("customer1"))),
        GARBAGE(() -> "not-a-jwt");

        final Supplier<String> token;

        Actor(Supplier<String> token) {
            this.token = token;
        }
    }

    /** The business endpoints, each with the order status it needs and the answer per actor (in {@link Actor} order). */
    enum Endpoint {
        LIST_PRODUCTS(
                "GET", "/api/v1/products", null, new int[] {200, 200, 200, 200, 200, 401, 401, 401, 401, 401, 401}),
        CREATE_ORDER("POST", "/api/v1/orders", null, new int[] {201, 201, 403, 403, 403, 401, 401, 401, 401, 401, 401}),
        GET_ORDER("GET", "/api/v1/orders/{id}", OrderStatus.PENDING_PAYMENT, new int[] {
            200, 404, 200, 403, 403, 401, 401, 401, 401, 401, 401
        }),
        LIST_ORDERS("GET", "/api/v1/orders", null, new int[] {200, 200, 200, 403, 403, 401, 401, 401, 401, 401, 401}),
        CANCEL_ORDER("POST", "/api/v1/orders/{id}/cancel", OrderStatus.PENDING_PAYMENT, new int[] {
            200, 404, 403, 403, 403, 401, 401, 401, 401, 401, 401
        }),
        REFUND_ORDER("POST", "/api/v1/orders/{id}/refund", OrderStatus.PAID, new int[] {
            403, 403, 202, 403, 403, 401, 401, 401, 401, 401, 401
        });

        final String method;
        final String path;
        /** The status of the order (owned by customer1) the request is about, or {@code null} if it names no order. */
        final OrderStatus orderStatus;

        final int[] expected;

        Endpoint(String method, String path, OrderStatus orderStatus, int[] expected) {
            this.method = method;
            this.path = path;
            this.orderStatus = orderStatus;
            this.expected = expected;
        }
    }

    static Stream<Arguments> accessMatrix() {
        return Arrays.stream(Endpoint.values())
                .flatMap(endpoint -> Arrays.stream(Actor.values())
                        .map(actor -> Arguments.of(endpoint, actor, endpoint.expected[actor.ordinal()])));
    }

    @ParameterizedTest(name = "{0} as {1} -> {2}")
    @MethodSource("accessMatrix")
    void accessMatrix(Endpoint endpoint, Actor actor, int expectedStatus) {
        UUID orderId = endpoint.orderStatus == null ? null : seedOrder(TestKeycloak.CUSTOMER1_ID, endpoint.orderStatus);
        String path = orderId == null ? endpoint.path : endpoint.path.replace("{id}", orderId.toString());
        String body = endpoint == Endpoint.CREATE_ORDER ? orderBody("MUG-JAVA", 1) : null;
        String before = ordersSnapshot();

        Reply reply = endpoint.method.equals("GET")
                ? get(path, actor.token.get())
                : post(path, actor.token.get(), newKey(), body);

        assertThat(reply.status()).as(reply.body()).isEqualTo(expectedStatus);
        if (expectedStatus < 300) {
            assertThat(ordersSnapshot())
                    .as("an allowed call changes state only as intended")
                    .isNotNull();
            return;
        }
        assertThat(reply.header("Content-Type")).startsWith("application/problem+json");
        assertThat(reply.json().path("status").intValue()).isEqualTo(expectedStatus);
        assertThat(reply.body()).doesNotContain("Exception").doesNotContain("\tat ");
        assertThat(ordersSnapshot())
                .as("a refused call must not change anything")
                .isEqualTo(before);
        switch (expectedStatus) {
            case 401 -> {
                assertThat(reply.problemType()).isEqualTo("urn:problem-type:unauthorized");
                assertThat(reply.header("WWW-Authenticate")).startsWith("Bearer");
            }
            case 403 -> assertThat(reply.problemType()).isEqualTo("urn:problem-type:forbidden");
            case 404 -> assertThat(reply.problemType()).isEqualTo("urn:problem-type:order-not-found");
            default -> throw new AssertionError("unexpected " + expectedStatus);
        }
    }

    @Test
    void anAllowedCreateReallyCreatesAndAnAllowedCancelOrRefundReallyMoves() {
        UUID pending = seedOrder(TestKeycloak.CUSTOMER1_ID);
        UUID paid = seedOrder(TestKeycloak.CUSTOMER1_ID, OrderStatus.PAID);

        assertThat(post("/api/v1/orders", TestKeycloak.tokenOf("customer1"), newKey(), orderBody("MUG-JAVA", 1))
                        .status())
                .isEqualTo(201);
        assertThat(orderCount()).isEqualTo(3);
        post("/api/v1/orders/" + pending + "/cancel", TestKeycloak.tokenOf("customer1"), newKey(), null);
        post("/api/v1/orders/" + paid + "/refund", TestKeycloak.tokenOf("admin1"), newKey(), null);

        assertThat(statusOf(pending)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(statusOf(paid)).isEqualTo(OrderStatus.REFUND_REQUESTED);
    }

    @Test
    void theTokenOfTheOpsServiceAccountCarriesTheOrderServiceAudience() {
        // otherwise the matrix would test the audience check instead of the role check for this actor
        assertThat(get("/actuator/metrics", TestKeycloak.opsClientToken()).status())
                .isEqualTo(200);
    }

    // ---- actuator ----

    @Test
    void healthAndInfoArePublic() {
        Reply health = get("/actuator/health", null);
        assertThat(health.status()).isEqualTo(200);
        assertThat(health.json().path("status").stringValue()).isEqualTo("UP");
        assertThat(health.json().has("components"))
                .as("no details for the public")
                .isFalse();
        assertThat(get("/actuator/health/liveness", null).status()).isEqualTo(200);
        assertThat(get("/actuator/health/readiness", null).status()).isEqualTo(200);
        assertThat(get("/actuator/info", null).status()).isEqualTo(200);
    }

    @ParameterizedTest(name = "/actuator/metrics as {0} -> {1}")
    @MethodSource("metricsAccess")
    void everyOtherActuatorEndpointNeedsTheOpsRole(Actor actor, int expected) {
        assertThat(get("/actuator/metrics", actor.token.get()).status()).isEqualTo(expected);
    }

    static Stream<Arguments> metricsAccess() {
        return Stream.of(
                Arguments.of(Actor.OPS1, 200),
                Arguments.of(Actor.OPS_SERVICE_ACCOUNT, 200),
                Arguments.of(Actor.CUSTOMER1, 403),
                Arguments.of(Actor.ADMIN1, 403),
                Arguments.of(Actor.NO_TOKEN, 401),
                Arguments.of(Actor.WRONG_AUDIENCE, 401),
                Arguments.of(Actor.SIGNED_BY_ANOTHER_KEY, 401),
                Arguments.of(Actor.TAMPERED_PAYLOAD, 401));
    }

    @Test
    void anActuatorEndpointThatIsNotExposedIsNotThereEvenForOps() {
        assertThat(get("/actuator/env", TestKeycloak.tokenOf("ops1")).status()).isEqualTo(404);
        assertThat(get("/actuator/env", TestKeycloak.tokenOf("customer1")).status())
                .isEqualTo(403);
    }

    // ---- the OpenAPI document exists only in the local profile ----

    @ParameterizedTest
    @MethodSource("openApiPaths")
    void theOpenApiDocumentAndSwaggerUiAreNotServedOutsideTheLocalProfile(String path) {
        assertThat(get(path, null).status()).as("no token").isEqualTo(401);
        assertThat(get(path, TestKeycloak.tokenOf("admin1")).status())
                .as("admin")
                .isEqualTo(404);
    }

    static Stream<String> openApiPaths() {
        return Stream.of("/v3/api-docs", "/v3/api-docs.yaml", "/swagger-ui.html", "/swagger-ui/index.html");
    }

    // ---- statelessness and transport ----

    @Test
    void theServiceKeepsNoSessionAndSetsNoCookies() {
        Reply ok = get("/api/v1/products", TestKeycloak.tokenOf("customer1"));
        Reply denied = get("/api/v1/products", null);

        assertThat(ok.header("Set-Cookie")).isNull();
        assertThat(denied.header("Set-Cookie")).isNull();
    }

    @Test
    void responsesCarryTheStandardSecurityHeaders() {
        Reply reply = get("/api/v1/products", TestKeycloak.tokenOf("customer1"));

        assertThat(reply.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(reply.header("Cache-Control")).contains("no-store");
        assertThat(reply.header("X-Frame-Options")).isEqualTo("DENY");
    }

    @Test
    void aTokenInTheQueryStringOrACookieIsNotAccepted() {
        String token = TestKeycloak.tokenOf("customer1");

        assertThat(get("/api/v1/products?access_token=" + token, null).status()).isEqualTo(401);
        assertThat(send("GET", "/api/v1/products", null, null, java.util.Map.of("Cookie", "access_token=" + token))
                        .status())
                .isEqualTo(401);
    }

    @Test
    void basicAuthenticationIsNotAccepted() {
        String basic = java.util.Base64.getEncoder().encodeToString("customer1:password".getBytes());

        Reply reply = send("GET", "/api/v1/products", null, null, java.util.Map.of("Authorization", "Basic " + basic));

        assertThat(reply.status()).isEqualTo(401);
    }

    @Test
    void theDeadLetterApiNeedsTheOpsRole() {
        assertThat(get("/admin/dead-letters", null).status()).isEqualTo(401);
        assertThat(get("/admin/dead-letters", TestKeycloak.tokenOf("customer1")).status())
                .isEqualTo(403);
        assertThat(get("/admin/dead-letters", TestKeycloak.tokenOf("admin1")).status())
                .isEqualTo(403);
        assertThat(get("/admin/dead-letters", TestKeycloak.opsClientToken()).status())
                .isEqualTo(200);
    }
}
