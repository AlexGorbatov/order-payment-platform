package com.altronixsoft.opp.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.altronixsoft.opp.platform.idempotency.testapp.DemoState;
import com.altronixsoft.opp.platform.idempotency.testapp.IdempotencyTestApplication;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The HTTP idempotency contract end to end: real server, real PostgreSQL, real HTTP. */
@SpringBootTest(
        classes = IdempotencyTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.flyway.locations=classpath:db/migration/test",
            "platform.idempotency.cleanup.enabled=false",
            "platform.idempotency.max-body-size=2KB",
            "platform.idempotency.in-progress-timeout=5m"
        })
class IdempotencyIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ORDER = "{\"sku\":\"BOOK-1\",\"quantity\":2}";

    private final HttpClient http =
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DemoState state;

    @Autowired
    SimpleMeterRegistry meters;

    @Autowired
    IdempotencyRepository repository;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDatabase.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", TestDatabase.POSTGRES::getUsername);
        registry.add("spring.datasource.password", TestDatabase.POSTGRES::getPassword);
    }

    @BeforeEach
    void clean() {
        jdbc.sql("TRUNCATE idempotency_record").update();
        state.reset();
    }

    // ----------------------------------------------------------------------------------------------- replay

    @Test
    void firstRequestRunsAndTheRepeatReplaysTheStoredResponse() throws Exception {
        String key = key();

        Reply first = post("/orders", key, ORDER);
        Reply second = post("/orders", key, ORDER);

        assertThat(first.status).isEqualTo(201);
        assertThat(first.header("Idempotent-Replayed")).isNull();
        assertThat(first.header("Location")).startsWith("/orders/");
        assertThat(second.status).isEqualTo(201);
        assertThat(second.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.body).isEqualTo(first.body);
        assertThat(second.header("Location")).isEqualTo(first.header("Location"));
        assertThat(second.header("Content-Type")).isEqualTo(first.header("Content-Type"));
        assertThat(state.executions("orders")).isEqualTo(1);
        assertThat(count("status = 'COMPLETED'")).isEqualTo(1);
        assertThat(meters.get("idempotency.replays").counter().count()).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void manyRepeatsAllReplayTheSameResponse() throws Exception {
        String key = key();
        Reply first = post("/orders", key, ORDER);

        for (int i = 0; i < 5; i++) {
            Reply repeat = post("/orders", key, ORDER);
            assertThat(repeat.body).isEqualTo(first.body);
            assertThat(repeat.header("Idempotent-Replayed")).isEqualTo("true");
        }
        assertThat(state.executions("orders")).isEqualTo(1);
    }

    @Test
    void theSameDataInAnotherKeyOrderOrFormattingIsTheSameRequest() throws Exception {
        String key = key();
        Reply first = post("/orders", key, "{\"sku\":\"BOOK-1\",\"quantity\":2}");

        Reply reordered = post("/orders", key, "{ \"quantity\": 2,\n  \"sku\": \"BOOK-1\" }");

        assertThat(reordered.status).isEqualTo(201);
        assertThat(reordered.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(reordered.body).isEqualTo(first.body);
        assertThat(state.executions("orders")).isEqualTo(1);
    }

    @Test
    void noContentResponsesAreReplayedToo() throws Exception {
        String key = key();

        Reply first = post("/no-content", key, ORDER);
        Reply second = post("/no-content", key, ORDER);

        assertThat(first.status).isEqualTo(204);
        assertThat(second.status).isEqualTo(204);
        assertThat(second.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.body).isEmpty();
        assertThat(state.executions("no-content")).isEqualTo(1);
    }

    // ----------------------------------------------------------------------------------------------- F17 key reuse

    /** F17: same key, different body. */
    @Test
    void theSameKeyWithADifferentBodyIsRejectedWith422() throws Exception {
        String key = key();
        post("/orders", key, ORDER);
        double conflicts = conflicts("key-reuse");

        Reply reused = post("/orders", key, "{\"sku\":\"BOOK-2\",\"quantity\":2}");

        assertThat(reused.status).isEqualTo(422);
        assertThat(reused.header("Content-Type")).startsWith("application/problem+json");
        JsonNode problem = reused.json();
        assertThat(problem.get("type").stringValue()).isEqualTo("urn:opp:problem:idempotency-key-reuse");
        assertThat(problem.get("status").intValue()).isEqualTo(422);
        assertThat(reused.header("Idempotent-Replayed")).isNull();
        assertThat(state.executions("orders")).isEqualTo(1);
        assertThat(conflicts("key-reuse")).isEqualTo(conflicts + 1);
    }

    @Test
    void theSameKeyOnAnotherPathOrWithAnotherQueryIsADifferentRequest() throws Exception {
        String key = key();
        post("/orders", key, ORDER);

        assertThat(post("/optional", key, ORDER).status).isEqualTo(422);
        assertThat(post("/orders?mode=other", key, ORDER).status).isEqualTo(422);
    }

    // ----------------------------------------------------------------------------------------------- F16 concurrency

    /** F16: ten concurrent requests with one key run the business method exactly once. */
    @Test
    void tenConcurrentRequestsWithTheSameKeyRunTheBusinessMethodOnce() throws Exception {
        String key = key();
        CountDownLatch start = new CountDownLatch(1);

        List<CompletableFuture<Reply>> calls = IntStream.range(0, 10)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await();
                        return post("/slow-orders", key, ORDER);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }))
                .toList();
        start.countDown();
        List<Reply> replies = calls.stream().map(CompletableFuture::join).toList();

        assertThat(state.executions("slow-orders")).isEqualTo(1);
        List<Reply> executed = replies.stream()
                .filter(r -> r.status == 201 && r.header("Idempotent-Replayed") == null)
                .toList();
        assertThat(executed).as("the one request that really ran").hasSize(1);
        assertThat(replies).allSatisfy(r -> assertThat(r.status).isIn(201, 409));
        replies.stream().filter(r -> r.status == 409).forEach(r -> {
            assertThat(r.header("Retry-After")).isEqualTo("1");
            assertThat(r.json().get("type").stringValue()).isEqualTo("urn:opp:problem:request-in-progress");
        });
        replies.stream()
                .filter(r -> r.status == 201)
                .forEach(r -> assertThat(r.body).isEqualTo(executed.getFirst().body));
        assertThat(replies.stream().filter(r -> r.status == 409).count())
                .as("most were in progress")
                .isGreaterThanOrEqualTo(1);

        // after completion a retry gets the stored response, not a conflict
        Reply later = post("/slow-orders", key, ORDER);
        assertThat(later.status).isEqualTo(201);
        assertThat(later.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(later.body).isEqualTo(executed.getFirst().body);
        assertThat(state.executions("slow-orders")).isEqualTo(1);
    }

    @Test
    void aRequestWhileTheFirstIsRunningGets409WithRetryAfterEvenWithADifferentBody() throws Exception {
        String key = key();
        CompletableFuture<Reply> running = CompletableFuture.supplyAsync(() -> {
            try {
                return post("/slow-orders", key, ORDER);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        await().atMost(Duration.ofSeconds(5)).until(() -> count("status = 'IN_PROGRESS'") == 1);

        Reply concurrent = post("/slow-orders", key, "{\"sku\":\"OTHER\",\"quantity\":1}");

        assertThat(concurrent.status).isEqualTo(409);
        assertThat(concurrent.header("Retry-After")).isEqualTo("1");
        assertThat(running.join().status).isEqualTo(201);
        assertThat(state.executions("slow-orders")).isEqualTo(1);
    }

    // ----------------------------------------------------------------------------------------------- failures

    @Test
    void anUnhandledExceptionRemovesTheRecordSoTheRetryRunsAgain() throws Exception {
        String key = key();

        Reply failed = post("/flaky-exception", key, ORDER);

        assertThat(failed.status).isEqualTo(500);
        assertThat(count("true")).as("record removed after the failure").isZero();

        Reply retry = post("/flaky-exception", key, ORDER);

        assertThat(retry.status).isEqualTo(201);
        assertThat(retry.header("Idempotent-Replayed")).isNull();
        assertThat(state.executions("flaky-exception")).isEqualTo(2);
        assertThat(post("/flaky-exception", key, ORDER).header("Idempotent-Replayed"))
                .isEqualTo("true");
    }

    @Test
    void anExplicit500RemovesTheRecordToo() throws Exception {
        String key = key();

        assertThat(post("/flaky-status", key, ORDER).status).isEqualTo(500);
        assertThat(count("true")).isZero();
        Reply retry = post("/flaky-status", key, ORDER);

        assertThat(retry.status).isEqualTo(201);
        assertThat(state.executions("flaky-status")).isEqualTo(2);
    }

    /** A 4xx written by the controller is stored byte for byte, headers included. */
    @Test
    void a4xxResponseWrittenByTheControllerIsReplayedExactly() throws Exception {
        String key = key();

        Reply first = post("/rejected", key, ORDER);
        Reply second = post("/rejected", key, ORDER);

        assertThat(first.status).isEqualTo(422);
        assertThat(second.status).isEqualTo(422);
        assertThat(second.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.body).isEqualTo(first.body);
        assertThat(second.header("Location")).isEqualTo(first.header("Location"));
        assertThat(second.header("Content-Type")).startsWith("application/problem+json");
        assertThat(state.executions("rejected")).isEqualTo(1);
    }

    /**
     * A validation failure is answered by Spring with {@code sendError}; the body comes from the container's error
     * page. The replay is produced the same way, so it has the same shape (only the timestamp differs), and the
     * controller does not run again.
     */
    @Test
    void aValidationFailureAnsweredThroughSendErrorIsReplayedInTheSameShape() throws Exception {
        String key = key();
        String invalid = "{\"sku\":\"\",\"quantity\":1}";

        Reply first = post("/orders", key, invalid);
        Reply second = post("/orders", key, invalid);

        assertThat(first.status).isEqualTo(400);
        assertThat(second.status).isEqualTo(400);
        assertThat(second.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.header("Content-Type")).isEqualTo(first.header("Content-Type"));
        assertThat(second.json().get("status")).isEqualTo(first.json().get("status"));
        assertThat(second.json().get("error")).isEqualTo(first.json().get("error"));
        assertThat(second.json().get("path")).isEqualTo(first.json().get("path"));
        assertThat(state.executions("orders")).isZero();
        assertThat(count("status = 'COMPLETED' AND response_status = 400")).isEqualTo(1);
    }

    @Test
    void conflictAndTooManyRequestsResponsesAreNotStoredSoTheClientCanRetry() throws Exception {
        String key = key();

        assertThat(post("/always-conflict", key, ORDER).status).isEqualTo(409);
        assertThat(post("/always-conflict", key, ORDER).status).isEqualTo(409);
        assertThat(post("/always-throttled", key, ORDER).status).isEqualTo(429);
        assertThat(post("/always-throttled", key, ORDER).status).isEqualTo(429);

        assertThat(state.executions("always-conflict")).isEqualTo(2);
        assertThat(state.executions("always-throttled")).isEqualTo(2);
        assertThat(count("true")).isZero();
    }

    // ----------------------------------------------------------------------------------------------- principals

    @Test
    void differentPrincipalsMayUseTheSameKeyWithoutConflict() throws Exception {
        String key = key();

        Reply alice = post("/orders", key, ORDER, basic("alice"));
        Reply bob = post("/orders", key, "{\"sku\":\"BOOK-9\",\"quantity\":9}", basic("bob"));
        Reply anonymous = post("/orders", key, "{\"sku\":\"BOOK-7\",\"quantity\":7}");
        Reply subjectOne = post("/orders", key, "{\"sku\":\"BOOK-3\",\"quantity\":3}", bearer("sub-1"));
        Reply subjectTwo = post("/orders", key, "{\"sku\":\"BOOK-4\",\"quantity\":4}", bearer("sub-2"));

        assertThat(List.of(alice, bob, anonymous, subjectOne, subjectTwo)).allSatisfy(r -> {
            assertThat(r.status).isEqualTo(201);
            assertThat(r.header("Idempotent-Replayed")).isNull();
        });
        assertThat(state.executions("orders")).isEqualTo(5);
        assertThat(jdbc.sql("SELECT principal FROM idempotency_record ORDER BY principal")
                        .query(String.class)
                        .list())
                .containsExactly("alice", "anonymous", "bob", "sub-1", "sub-2");
    }

    @Test
    void thePrincipalOfAJwtIsItsSubjectAndTheSamePrincipalReplays() throws Exception {
        String key = key();

        Reply first = post("/orders", key, ORDER, bearer("customer-42"));
        Reply repeat = post("/orders", key, ORDER, bearer("customer-42"));
        Reply other = post("/orders", key, ORDER, bearer("customer-43"));

        assertThat(repeat.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(repeat.body).isEqualTo(first.body);
        assertThat(other.header("Idempotent-Replayed")).isNull();
        assertThat(count("principal = 'customer-42'")).isEqualTo(1);
    }

    // ----------------------------------------------------------------------------------------------- TTL, abandoned
    // claims

    @Test
    void afterTheTtlTheKeyIsFreeAgain() throws Exception {
        String key = key();
        Reply first = post("/short-lived", key, ORDER);

        Thread.sleep(1_300);
        Reply afterExpiry = post("/short-lived", key, "{\"sku\":\"NEW\",\"quantity\":5}");

        assertThat(afterExpiry.status).as("a new request, not a 422 key reuse").isEqualTo(201);
        assertThat(afterExpiry.header("Idempotent-Replayed")).isNull();
        assertThat(afterExpiry.body).isNotEqualTo(first.body);
        assertThat(state.executions("short-lived")).isEqualTo(2);
    }

    @Test
    void anExpiredRecordWithTheSameRequestRunsAgainInsteadOfReplaying() throws Exception {
        String key = key();
        Reply first = post("/orders", key, ORDER);
        jdbc.sql("UPDATE idempotency_record SET expires_at = now() - interval '1 second'")
                .update();

        Reply again = post("/orders", key, ORDER);

        assertThat(again.header("Idempotent-Replayed")).isNull();
        assertThat(again.body).isNotEqualTo(first.body);
        assertThat(state.executions("orders")).isEqualTo(2);
    }

    @Test
    void cleanupDeletesOnlyExpiredRecords() throws Exception {
        post("/orders", key(), ORDER);
        post("/orders", key(), "{\"sku\":\"B\",\"quantity\":1}");
        post("/orders", key(), "{\"sku\":\"C\",\"quantity\":1}");
        jdbc.sql("""
                        UPDATE idempotency_record SET expires_at = now() - interval '1 hour'
                        WHERE request_hash IN (SELECT request_hash FROM idempotency_record LIMIT 2)
                        """).update();
        IdempotencyCleanup cleanup = new IdempotencyCleanup(repository, 1);

        assertThat(cleanup.cleanUp()).isEqualTo(2);

        assertThat(count("true")).isEqualTo(1);
        assertThat(cleanup.cleanUp()).isZero();
    }

    @Test
    void anAbandonedClaimIsTakenOverButAFreshOneIsRespected() throws Exception {
        String key = key();
        String hash = RequestFingerprint.of(
                "POST", "/orders", null, "application/json", ORDER.getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                        INSERT INTO idempotency_record (principal, idem_key, request_hash, status, created_at, expires_at)
                        VALUES ('anonymous', :key, :hash, 'IN_PROGRESS', now(), now() + interval '1 day')
                        """).param("key", key).param("hash", hash).update();

        assertThat(post("/orders", key, ORDER).status).as("claim still fresh").isEqualTo(409);

        jdbc.sql("UPDATE idempotency_record SET created_at = now() - interval '10 minutes'")
                .update();
        Reply takeover = post("/orders", key, ORDER);

        assertThat(takeover.status)
                .as("the process that claimed it is long gone")
                .isEqualTo(201);
        assertThat(state.executions("orders")).isEqualTo(1);
    }

    // ----------------------------------------------------------------------------------------------- the header

    @Test
    void aMissingKeyOnARequiredEndpointIsA400ProblemDetail() throws Exception {
        Reply reply = post("/orders", null, ORDER);

        assertThat(reply.status).isEqualTo(400);
        assertThat(reply.header("Content-Type")).startsWith("application/problem+json");
        assertThat(reply.json().get("type").stringValue()).isEqualTo("urn:opp:problem:idempotency-key-required");
        assertThat(state.executions("orders")).isZero();
        assertThat(count("true")).isZero();
    }

    @Test
    void malformedKeysAreRejected() throws Exception {
        for (String bad : new String[] {"", " ", "has space", "tab\tkey", "x".repeat(256)}) {
            Reply reply = post("/orders", bad, ORDER);
            assertThat(reply.status).as("key '%s'", bad).isEqualTo(400);
            assertThat(reply.json().get("type").stringValue()).isEqualTo("urn:opp:problem:idempotency-key-invalid");
        }
        assertThat(state.executions("orders")).isZero();
    }

    @Test
    void keysFromOneToTwoHundredFiftyFivePrintableCharactersAreAccepted() throws Exception {
        for (String good : new String[] {"k", "x".repeat(255), "a-b_c.d:e/f=g+h~!@#$%^&*()"}) {
            assertThat(post("/orders", good, ORDER).status).as("key '%s'", good).isEqualTo(201);
        }
    }

    @Test
    void twoKeyHeadersAreRejected() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/orders"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "one")
                .header("Idempotency-Key", "two")
                .POST(HttpRequest.BodyPublishers.ofString(ORDER))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void anOptionalEndpointRunsEveryTimeWithoutAKeyAndDeduplicatesWithOne() throws Exception {
        post("/optional", null, ORDER);
        post("/optional", null, ORDER);
        assertThat(state.executions("optional")).isEqualTo(2);
        assertThat(count("true")).isZero();

        String key = key();
        Reply first = post("/optional", key, ORDER);
        Reply second = post("/optional", key, ORDER);

        assertThat(second.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.body).isEqualTo(first.body);
        assertThat(state.executions("optional")).isEqualTo(3);
    }

    @Test
    void endpointsWithoutTheAnnotationIgnoreTheHeader() throws Exception {
        String key = key();

        post("/plain", key, ORDER);
        post("/plain", key, ORDER);

        assertThat(state.executions("plain")).isEqualTo(2);
        assertThat(count("true")).isZero();
        assertThat(get("/orders/abc", key).status).isEqualTo(200);
    }

    @Test
    void classLevelAnnotationAppliesAndAMethodLevelOneOverridesIt() throws Exception {
        String key = key();

        assertThat(post("/class-level/inherits", null, "{}").status)
                .as("required by the class")
                .isEqualTo(400);
        Reply first = post("/class-level/inherits", key, "{}");
        Reply second = post("/class-level/inherits", key, "{}");
        assertThat(second.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.body).isEqualTo(first.body);

        assertThat(post("/class-level/overrides", null, "{}").status)
                .as("optional by the method")
                .isEqualTo(200);
    }

    @Test
    void aBodyAboveTheLimitIsRejectedBeforeItReachesTheController() throws Exception {
        String big = "{\"sku\":\"" + "x".repeat(3_000) + "\",\"quantity\":1}";

        Reply reply = post("/orders", key(), big);

        assertThat(reply.status).isEqualTo(413);
        assertThat(reply.json().get("type").stringValue()).isEqualTo("urn:opp:problem:request-body-too-large");
        assertThat(state.executions("orders")).isZero();
    }

    @Test
    void formPostsAreHashedByTheirParametersAndStillReachTheController() throws Exception {
        String key = key();

        Reply first = form("/form", key, "sku=BOOK&quantity=3");
        Reply reordered = form("/form", key, "quantity=3&sku=BOOK");
        Reply different = form("/form", key, "sku=BOOK&quantity=4");

        assertThat(first.status).isEqualTo(201);
        assertThat(first.json().get("sku").stringValue())
                .as("controller received the parameters")
                .isEqualTo("BOOK");
        assertThat(reordered.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(different.status).isEqualTo(422);
        assertThat(state.executions("form")).isEqualTo(1);
    }

    // ----------------------------------------------------------------------------------------------- helpers

    private String key() {
        return UUID.randomUUID().toString();
    }

    private double conflicts(String reason) {
        var counter = meters.find("idempotency.conflicts").tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    private int count(String where) {
        return jdbc.sql("SELECT count(*) FROM idempotency_record WHERE " + where)
                .query(Integer.class)
                .single();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String basic(String user) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":pw").getBytes(StandardCharsets.UTF_8));
    }

    private static String bearer(String subject) {
        return "Bearer sub_" + subject;
    }

    private Reply post(String path, String key, String body, String... authorization) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) {
            builder.header("Idempotency-Key", key);
        }
        for (String value : authorization) {
            builder.header("Authorization", value);
        }
        return send(builder.build());
    }

    private Reply form(String path, String key, String body) throws Exception {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
    }

    private Reply get(String path, String key) throws Exception {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Idempotency-Key", key)
                .GET()
                .build());
    }

    private Reply send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        Map<String, String> headers = new java.util.HashMap<>();
        response.headers().map().forEach((name, values) -> headers.put(name.toLowerCase(), values.getFirst()));
        return new Reply(response.statusCode(), response.body(), headers);
    }

    private record Reply(int status, String body, Map<String, String> headers) {

        String header(String name) {
            return headers.get(name.toLowerCase());
        }

        JsonNode json() {
            return JSON.readTree(body);
        }
    }
}
