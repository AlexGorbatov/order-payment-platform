package com.altronixsoft.opp.e2e.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The platform as its clients use it: HTTP with real tokens against {@code order-service} and {@code payment-service},
 * plus the waiting that an asynchronous system needs ({@code awaitOrder}, {@code awaitPayment}, ...). Remembers the orders
 * placed through it, so the invariants at the end of a test know which ones belong to the test.
 */
public final class TestClient {

    /** How long a scenario waits for the platform to catch up before it fails. */
    public static final Duration ASYNC = Duration.ofSeconds(45);

    private static final Duration POLL = Duration.ofMillis(100);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** The body of a request line: a catalog SKU and a quantity. */
    public record Line(String sku, int quantity) {}

    /** The default basket: one book and two mugs, 6097 cents. */
    public static final List<Line> BASKET = List.of(new Line("BOOK-CLEAN-CODE", 1), new Line("MUG-JAVA", 2));

    public static final long BASKET_TOTAL_MINOR = 3499 + 2 * 1299;

    /** An HTTP answer. */
    public record Reply(int status, String body, HttpHeaders headers) {

        public JsonNode json() {
            return JSON.readTree(body);
        }

        public String header(String name) {
            return headers.firstValue(name).orElse(null);
        }
    }

    /** The order as the API reports it. */
    public record Order(UUID id, String status, long totalMinor, boolean disputed, JsonNode raw) {

        public static Order of(JsonNode json) {
            return new Order(
                    UUID.fromString(json.get("id").stringValue()),
                    json.get("status").stringValue(),
                    json.get("total").get("amountMinor").asLong(),
                    json.get("disputed").asBoolean(),
                    json);
        }
    }

    /** The payment as the API reports it. */
    public record Payment(
            UUID paymentId,
            UUID orderId,
            String status,
            String stripePaymentIntentId,
            String clientSecret,
            String lastErrorCode,
            boolean disputed) {

        public static Payment of(JsonNode json) {
            return new Payment(
                    UUID.fromString(json.get("paymentId").stringValue()),
                    UUID.fromString(json.get("orderId").stringValue()),
                    json.get("status").stringValue(),
                    text(json, "stripePaymentIntentId"),
                    text(json, "clientSecret"),
                    text(json, "lastErrorCode"),
                    json.path("disputed").asBoolean());
        }
    }

    private final Platform platform;
    private final Set<UUID> orders = new CopyOnWriteArraySet<>();
    private final Set<UUID> adopted = new CopyOnWriteArraySet<>();

    public TestClient(Platform platform) {
        this.platform = platform;
    }

    /** The orders placed through this client since {@link #forgetOrders()}: every one of them gets a payment. */
    public Set<UUID> orders() {
        return Collections.unmodifiableSet(orders);
    }

    public void forgetOrders() {
        orders.clear();
        adopted.clear();
    }

    /** Orders created by other means (injected by a test); the invariants cover them, but they need no payment. */
    public void adopt(UUID orderId) {
        adopted.add(orderId);
    }

    public Set<UUID> adopted() {
        return Collections.unmodifiableSet(adopted);
    }

    // ---------------------------------------------------------------------------------------------- orders

    public Order placeOrder() {
        return placeOrder(Actor.CUSTOMER, UUID.randomUUID().toString(), BASKET);
    }

    public Order placeOrder(Actor actor, String idempotencyKey, List<Line> lines) {
        Reply reply = createOrder(actor, idempotencyKey, lines);
        assertThat(reply.status()).as("POST /orders: %s", reply.body()).isEqualTo(201);
        Order order = Order.of(reply.json());
        orders.add(order.id());
        return order;
    }

    /** The raw answer, for scenarios that care about the status and headers (idempotency). */
    public Reply createOrder(Actor actor, String idempotencyKey, List<Line> lines) {
        return createOrder(actor, idempotencyKey, lines, Map.of());
    }

    public Reply createOrder(Actor actor, String idempotencyKey, List<Line> lines, Map<String, String> headers) {
        Map<String, Object> body = Map.of(
                "items",
                lines.stream()
                        .map(l -> Map.of("sku", l.sku(), "quantity", l.quantity()))
                        .toList());
        Reply reply = post(
                platform.orderService().baseUrl() + "/api/v1/orders",
                actor,
                JSON.writeValueAsString(body),
                withIdempotencyKey(idempotencyKey, headers));
        if (reply.status() == 201) {
            orders.add(UUID.fromString(reply.json().get("id").stringValue()));
        }
        return reply;
    }

    private static Map<String, String> withIdempotencyKey(String key, Map<String, String> headers) {
        Map<String, String> all = new LinkedHashMap<>(headers);
        all.put("Idempotency-Key", key);
        return all;
    }

    public Order order(Actor actor, UUID orderId) {
        Reply reply = get(platform.orderService().baseUrl() + "/api/v1/orders/" + orderId, actor);
        assertThat(reply.status()).as("GET order %s: %s", orderId, reply.body()).isEqualTo(200);
        return Order.of(reply.json());
    }

    public Reply cancelOrder(Actor actor, UUID orderId) {
        return post(
                platform.orderService().baseUrl() + "/api/v1/orders/" + orderId + "/cancel",
                actor,
                null,
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
    }

    public Reply refundOrder(Actor actor, UUID orderId, String idempotencyKey) {
        return post(
                platform.orderService().baseUrl() + "/api/v1/orders/" + orderId + "/refund",
                actor,
                null,
                Map.of("Idempotency-Key", idempotencyKey));
    }

    // ---------------------------------------------------------------------------------------------- payments

    /** The payment of the order; empty until payment-service has created it. */
    public Optional<Payment> payment(Actor actor, UUID orderId) {
        Reply reply = get(platform.paymentService().baseUrl() + "/api/v1/payments/by-order/" + orderId, actor);
        if (reply.status() == 404) {
            return Optional.empty();
        }
        assertThat(reply.status())
                .as("GET payment of %s: %s", orderId, reply.body())
                .isEqualTo(200);
        return Optional.of(Payment.of(reply.json()));
    }

    public Reply paymentReply(Actor actor, UUID orderId) {
        return get(platform.paymentService().baseUrl() + "/api/v1/payments/by-order/" + orderId, actor);
    }

    /** Pays the way the demo does: the test-support endpoint confirms the PaymentIntent with a Stripe test card. */
    public JsonNode confirm(UUID orderId, String scenario) {
        Reply reply = post(
                platform.paymentService().baseUrl() + "/api/v1/test-support/payments/by-order/" + orderId
                        + "/confirm?scenario=" + scenario,
                Actor.CUSTOMER,
                null,
                Map.of());
        assertThat(reply.status()).as("confirm %s: %s", scenario, reply.body()).isEqualTo(202);
        return reply.json();
    }

    // ---------------------------------------------------------------------------------------------- operations

    public Reply runReconciliation() {
        return post(platform.paymentService().baseUrl() + "/admin/reconciliation/run", Actor.OPS, null, Map.of());
    }

    /** Dead letters of the service ({@code order-service} or {@code payment-service}) in the given status. */
    public List<JsonNode> deadLetters(ServiceProcess service, String status) {
        Reply reply = get(service.baseUrl() + "/admin/dead-letters?size=100&status=" + status, Actor.OPS);
        assertThat(reply.status()).as("GET dead letters: %s", reply.body()).isEqualTo(200);
        List<JsonNode> items = new ArrayList<>();
        reply.json().get("items").forEach(items::add);
        return items;
    }

    public Reply replayDeadLetter(ServiceProcess service, String id) {
        return post(service.baseUrl() + "/admin/dead-letters/" + id + "/replay", Actor.OPS, null, Map.of());
    }

    public Reply resolveDeadLetter(ServiceProcess service, String id, String comment) {
        return post(
                service.baseUrl() + "/admin/dead-letters/" + id + "/resolve",
                Actor.OPS,
                JSON.writeValueAsString(Map.of("comment", comment)),
                Map.of());
    }

    // ---------------------------------------------------------------------------------------------- waiting

    public Order awaitOrder(UUID orderId, String status) {
        return await("order " + orderId + " to be " + status)
                .until(
                        () -> order(Actor.ADMIN, orderId),
                        order -> order.status().equals(status));
    }

    public Payment awaitPayment(UUID orderId, String status) {
        return await("payment of order " + orderId + " to be " + status)
                .until(
                        () -> payment(Actor.ADMIN, orderId),
                        found -> found.map(p -> p.status().equals(status)).orElse(false))
                .orElseThrow();
    }

    /** The payment once payment-service has a PaymentIntent for it and Stripe waits for a card. */
    public Payment awaitPayable(UUID orderId) {
        return awaitPayment(orderId, "REQUIRES_PAYMENT_METHOD");
    }

    private static org.awaitility.core.ConditionFactory await(String what) {
        return org.awaitility.Awaitility.await(what).atMost(ASYNC).pollInterval(POLL);
    }

    /** Repeats {@code assertion} until it holds or {@link #ASYNC} passes. */
    public static void eventually(String what, Runnable assertion) {
        await(what).untilAsserted(assertion::run);
    }

    // ---------------------------------------------------------------------------------------------- HTTP

    public Reply get(String url, Actor actor) {
        return send(HttpRequest.newBuilder(URI.create(url)).GET(), actor, Map.of());
    }

    public Reply post(String url, Actor actor, String json, Map<String, String> headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .POST(json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) {
            request.header("Content-Type", "application/json");
        }
        return send(request, actor, headers);
    }

    private Reply send(HttpRequest.Builder request, Actor actor, Map<String, String> headers) {
        request.timeout(Duration.ofSeconds(30)).header("Accept", "application/json, application/problem+json");
        if (actor != null) {
            request.header("Authorization", "Bearer " + platform.tokens().of(actor));
        }
        new LinkedHashMap<>(headers).forEach(request::header);
        try {
            HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.body(), response.headers());
        } catch (IOException e) {
            throw new IllegalStateException("HTTP call failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String text(JsonNode json, String field) {
        JsonNode node = json.get(field);
        return node == null || node.isNull() ? null : node.stringValue();
    }
}
