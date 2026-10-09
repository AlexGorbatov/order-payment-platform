package com.altronixsoft.opp.order;

import com.altronixsoft.opp.order.application.OrderRepository;
import com.altronixsoft.opp.order.application.PlaceOrderCommand;
import com.altronixsoft.opp.order.application.PlaceOrderService;
import com.altronixsoft.opp.order.domain.Order;
import com.altronixsoft.opp.order.domain.OrderStatus;
import com.altronixsoft.opp.order.domain.RefundReason;
import com.altronixsoft.opp.order.domain.Trigger;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole service on a random port, with a real PostgreSQL and a real Keycloak, called over HTTP with real tokens.
 * Subclasses share one application context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractApiIT {

    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OrderRepository orders;

    @Autowired
    PlaceOrderService placeOrder;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDatabase.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", TestDatabase.POSTGRES::getUsername);
        registry.add("spring.datasource.password", TestDatabase.POSTGRES::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", TestKeycloak::issuer);
    }

    @BeforeEach
    void cleanDatabase() {
        jdbc.sql("TRUNCATE order_status_history, order_item, orders, idempotency_record")
                .update();
    }

    // ---- HTTP ----

    /** An HTTP answer. */
    record Reply(int status, HttpHeaders headers, String body) {

        JsonNode json() {
            return JSON.readTree(body);
        }

        String header(String name) {
            return headers.firstValue(name).orElse(null);
        }

        String problemType() {
            JsonNode type = json().path("type");
            return type.isMissingNode() ? null : type.stringValue();
        }
    }

    Reply send(String method, String path, String token, String body, Map<String, String> extraHeaders) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .method(
                        method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        extraHeaders.forEach(request::setHeader);
        try {
            HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.headers(), response.body());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    Reply get(String path, String token) {
        return send("GET", path, token, null, Map.of());
    }

    Reply post(String path, String token, String idempotencyKey, String body) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (idempotencyKey != null) {
            headers.put("Idempotency-Key", idempotencyKey);
        }
        return send("POST", path, token, body, headers);
    }

    /** A request body for an order of the given SKU/quantity pairs. */
    static String orderBody(Object... skuAndQuantity) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < skuAndQuantity.length; i += 2) {
            items.append(i == 0 ? "" : ",")
                    .append("{\"sku\":\"")
                    .append(skuAndQuantity[i])
                    .append("\",\"quantity\":")
                    .append(skuAndQuantity[i + 1])
                    .append("}");
        }
        return "{\"items\":[" + items + "]}";
    }

    static String newKey() {
        return UUID.randomUUID().toString();
    }

    // ---- data ----

    private static Trigger now() {
        return Trigger.api(Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    /** An order of {@code customerId}: 2 × MUG-JAVA + 1 × STICKERS-PACK = 3097 EUR cents, pending payment. */
    UUID seedOrder(String customerId) {
        return placeOrder
                .place(new PlaceOrderCommand(
                        customerId,
                        List.of(
                                new PlaceOrderCommand.Line("MUG-JAVA", 2),
                                new PlaceOrderCommand.Line("STICKERS-PACK", 1))))
                .id();
    }

    /** An order of {@code customerId} that has been brought to {@code status} through legal transitions. */
    UUID seedOrder(String customerId, OrderStatus status) {
        UUID id = seedOrder(customerId);
        if (status == OrderStatus.PENDING_PAYMENT) {
            return id;
        }
        Order order = orders.findById(id).orElseThrow();
        switch (status) {
            case CANCELLED -> order.cancel(com.altronixsoft.opp.order.domain.CancelReason.CUSTOMER, now());
            case PAID -> order.markPaid(now());
            case REFUND_REQUESTED -> {
                order.markPaid(now());
                order.requestRefund(RefundReason.ADMIN, UUID.randomUUID(), now());
            }
            case REFUND_FAILED -> {
                order.markPaid(now());
                order.requestRefund(RefundReason.ADMIN, UUID.randomUUID(), now());
                order.markRefundFailed("card_closed", now());
            }
            case REFUNDED -> {
                order.markPaid(now());
                order.requestRefund(RefundReason.ADMIN, UUID.randomUUID(), now());
                order.markRefunded(now());
            }
            default -> throw new IllegalArgumentException(status.name());
        }
        orders.save(order);
        return id;
    }

    OrderStatus statusOf(UUID id) {
        return orders.findById(id).orElseThrow().status();
    }

    int orderCount() {
        return jdbc.sql("SELECT count(*) FROM orders").query(Integer.class).single();
    }

    /** Everything that can change about orders, to prove a refused request changed nothing. */
    String ordersSnapshot() {
        return jdbc.sql("""
                        SELECT o.id || ':' || o.status || ':' || o.version || ':' ||
                               (SELECT count(*) FROM order_status_history h WHERE h.order_id = o.id)
                        FROM orders o ORDER BY o.id""").query(String.class).list().toString();
    }
}
