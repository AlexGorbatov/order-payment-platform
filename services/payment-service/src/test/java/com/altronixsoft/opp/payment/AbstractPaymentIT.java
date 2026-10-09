package com.altronixsoft.opp.payment;

import static org.awaitility.Awaitility.await;

import com.altronixsoft.opp.payment.adapter.in.job.PaymentInitiationJob;
import com.altronixsoft.opp.payment.adapter.in.job.WebhookProcessorJob;
import com.altronixsoft.opp.payment.application.PaymentEventPublisher;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole service on a random port with a real PostgreSQL, Kafka and Keycloak and a WireMock that plays Stripe, called
 * over HTTP with real tokens and fed with order events on Kafka. Subclasses share one application context.
 *
 * <p>The initiation worker is not scheduled: a test runs {@link PaymentInitiationJob#run()} itself and moves the
 * {@link MutableClock} to make retries due, which makes every scenario deterministic. The SDK does not retry on its own
 * ({@code stripe.max-network-retries=0}), so one run is one call to Stripe; the circuit breaker is out of the way. The
 * webhook processor is not scheduled either: a test runs {@link WebhookProcessorJob#run()}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "payment.initiation.enabled=false",
            "payment.initiation.retry-jitter=0",
            "payment.initiation.retry-base-delay=10s",
            "payment.initiation.lease=5m",
            "payment.webhook-processor.enabled=false",
            "payment.webhook-processor.retry-jitter=0",
            "payment.webhook-processor.retry-base-delay=10s",
            "payment.webhook-processor.retry-max-attempts=3",
            "stripe.webhook.signing-secrets=" + AbstractPaymentIT.WEBHOOK_SECRET + ","
                    + AbstractPaymentIT.PREVIOUS_WEBHOOK_SECRET,
            "platform.test-support.enabled=true",
            "stripe.api-key=sk_test_it_payment",
            "stripe.max-network-retries=0",
            "stripe.connect-timeout=2s",
            "stripe.read-timeout=5s",
            "stripe.circuit-breaker.minimum-number-of-calls=1000",
            "stripe.circuit-breaker.sliding-window-size=1000",
            "platform.outbox.relay.initial-delay=0s",
            "platform.outbox.relay.fixed-delay=100ms",
            "platform.consumer.retry.blocking-interval=100ms",
            "platform.consumer.retry.topic-delays=200ms,400ms,800ms",
            "platform.dead-letters.persister.metadata-refresh=1s"
        })
@Import(TestClockConfiguration.class)
abstract class AbstractPaymentIT {

    /** The webhook signing secrets of the tests: the current one and one being rolled out. Not real secrets. */
    static final String WEBHOOK_SECRET = "whsec_it_current";

    static final String PREVIOUS_WEBHOOK_SECRET = "whsec_it_previous";

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Duration ASYNC = Duration.ofSeconds(30);
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
    MutableClock clock;

    @Autowired
    PaymentInitiationJob job;

    /** A spy on the outbox publisher, to play a crash after Stripe answered and before the result is committed (F05). */
    @MockitoSpyBean
    PaymentEventPublisher events;

    PaymentKafka kafka;

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDatabase.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", TestDatabase.POSTGRES::getUsername);
        registry.add("spring.datasource.password", TestDatabase.POSTGRES::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", TestKeycloak::issuer);
        registry.add("spring.kafka.bootstrap-servers", TestKafka::bootstrapServers);
        registry.add("stripe.api-base", TestStripe::baseUrl);
    }

    @BeforeEach
    void cleanUp() {
        clock.reset();
        TestStripe.reset();
        jdbc.sql("TRUNCATE payment_status_history, refund, payment, stripe_webhook_event, outbox_event, "
                        + "inbox_message, dead_letter_message")
                .update();
        kafka = new PaymentKafka();
    }

    @AfterEach
    void disconnect() {
        kafka.close();
    }

    // ---- data ----

    /** Waits until the consumer has created the payment of {@code orderId}; returns its id. */
    UUID awaitPayment(UUID orderId) {
        return await().atMost(ASYNC)
                .until(() -> paymentIdOf(orderId), java.util.Optional::isPresent)
                .orElseThrow();
    }

    java.util.Optional<UUID> paymentIdOf(UUID orderId) {
        return jdbc.sql("SELECT id FROM payment WHERE order_id = :orderId")
                .param("orderId", orderId)
                .query(UUID.class)
                .optional();
    }

    String statusOf(UUID paymentId) {
        return jdbc.sql("SELECT status FROM payment WHERE id = :id")
                .param("id", paymentId)
                .query(String.class)
                .single();
    }

    int paymentCount() {
        return jdbc.sql("SELECT count(*) FROM payment").query(Integer.class).single();
    }

    /** Every cell of every table, as text: to prove a value is nowhere in the database. */
    String wholeDatabase() {
        List<String> tables = jdbc.sql("SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'")
                .query(String.class)
                .list();
        StringBuilder all = new StringBuilder();
        for (String table : tables) {
            jdbc.sql("SELECT t::text FROM \"" + table + "\" t")
                    .query(String.class)
                    .list()
                    .forEach(row -> all.append(table).append(": ").append(row).append('\n'));
        }
        return all.toString();
    }

    String paymentSnapshot() {
        return jdbc.sql("SELECT p::text FROM payment p ORDER BY id")
                        .query(String.class)
                        .list()
                        .toString()
                + jdbc.sql("SELECT count(*) FROM payment_status_history")
                        .query(Integer.class)
                        .single();
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

    Reply send(String method, String path, String token) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
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
        return send("GET", path, token);
    }

    Reply post(String path, String token) {
        return send("POST", path, token);
    }

    static Map<String, String> headers(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
