package com.altronixsoft.opp.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.altronixsoft.opp.order.domain.OrderStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/** The behaviour of the endpoints for callers who are allowed in (access is {@link OrderApiSecurityIT}). */
class OrderApiIT extends AbstractApiIT {

    private static final String CUSTOMER1 = TestKeycloak.CUSTOMER1_ID;
    private static final String CUSTOMER2 = TestKeycloak.CUSTOMER2_ID;

    private static String customer1() {
        return TestKeycloak.tokenOf("customer1");
    }

    private static String customer2() {
        return TestKeycloak.tokenOf("customer2");
    }

    private static String admin() {
        return TestKeycloak.tokenOf("admin1");
    }

    // ------------------------------------------------------------------------------------------------------------

    @Nested
    class Products {

        @Test
        void listsTheActiveProductsWithServerPricesSortedBySku() {
            Reply reply = get("/api/v1/products", customer1());

            assertThat(reply.status()).isEqualTo(200);
            JsonNode products = reply.json();
            assertThat(products.size()).isEqualTo(5);
            assertThat(products.findValuesAsString("sku"))
                    .containsExactly("BOOK-CLEAN-CODE", "BOOK-DDIA", "MUG-JAVA", "STICKERS-PACK", "TSHIRT-OPP");
            JsonNode mug = products.get(2);
            assertThat(mug.path("name").stringValue()).isEqualTo("Coffee mug \"Java\"");
            assertThat(mug.path("price").path("amountMinor").longValue()).isEqualTo(1299);
            assertThat(mug.path("price").path("currency").stringValue()).isEqualTo("EUR");
        }

        @Test
        void aDiscontinuedProductIsNotOffered() {
            assertThat(get("/api/v1/products", customer1()).body()).doesNotContain("DISCONTINUED-MOUSE");
        }
    }

    // ------------------------------------------------------------------------------------------------------------

    @Nested
    class Create {

        @Test
        void placesAnOrderPricedFromTheCatalog() {
            Reply reply = post("/api/v1/orders", customer1(), newKey(), orderBody("MUG-JAVA", 2, "STICKERS-PACK", 1));

            assertThat(reply.status()).isEqualTo(201);
            JsonNode order = reply.json();
            UUID id = UUID.fromString(order.path("id").stringValue());
            assertThat(reply.header("Location")).endsWith("/api/v1/orders/" + id);
            assertThat(reply.header("Content-Type")).startsWith("application/json");
            assertThat(order.path("status").stringValue()).isEqualTo("PENDING_PAYMENT");
            assertThat(order.path("customerId").stringValue()).isEqualTo(CUSTOMER1);
            assertThat(order.path("total").path("amountMinor").longValue()).isEqualTo(2 * 1299 + 499);
            assertThat(order.path("total").path("currency").stringValue()).isEqualTo("EUR");
            assertThat(order.path("disputed").booleanValue()).isFalse();
            JsonNode lines = order.path("items");
            assertThat(lines.size()).isEqualTo(2);
            assertThat(lines.get(0).path("sku").stringValue()).isEqualTo("MUG-JAVA");
            assertThat(lines.get(0).path("name").stringValue()).isEqualTo("Coffee mug \"Java\"");
            assertThat(lines.get(0).path("unitPrice").path("amountMinor").longValue())
                    .isEqualTo(1299);
            assertThat(lines.get(0).path("lineTotal").path("amountMinor").longValue())
                    .isEqualTo(2598);
            assertThat(order.path("history").size()).isEqualTo(1);
            assertThat(order.path("history").get(0).path("to").stringValue()).isEqualTo("PENDING_PAYMENT");
            assertThat(order.path("history").get(0).has("from")).isFalse();

            assertThat(orders.findById(id)).hasValueSatisfying(stored -> {
                assertThat(stored.customerId()).isEqualTo(CUSTOMER1);
                assertThat(stored.total().amountMinor()).isEqualTo(3097);
            });
        }

        @Test
        void pricesSentByTheClientAreIgnored() {
            String body = """
                    {"customerId":"%s","status":"PAID","total":{"amountMinor":1,"currency":"EUR"},"totalMinor":1,
                     "items":[{"sku":"MUG-JAVA","quantity":2,"price":1,"unitPrice":{"amountMinor":1,"currency":"USD"},
                               "unitPriceMinor":1,"name":"Free mug","currency":"USD","lineTotal":1}]}""".formatted(CUSTOMER2);

            Reply reply = post("/api/v1/orders", customer1(), newKey(), body);

            assertThat(reply.status()).isEqualTo(201);
            JsonNode order = reply.json();
            assertThat(order.path("total").path("amountMinor").longValue()).isEqualTo(2598);
            assertThat(order.path("total").path("currency").stringValue()).isEqualTo("EUR");
            assertThat(order.path("status").stringValue()).isEqualTo("PENDING_PAYMENT");
            assertThat(order.path("customerId").stringValue())
                    .as("the owner is the token's sub")
                    .isEqualTo(CUSTOMER1);
            assertThat(order.path("items").get(0).path("name").stringValue()).isEqualTo("Coffee mug \"Java\"");
            assertThat(order.path("items")
                            .get(0)
                            .path("unitPrice")
                            .path("amountMinor")
                            .longValue())
                    .isEqualTo(1299);
            assertThat(jdbc.sql("SELECT total_minor FROM orders")
                            .query(Long.class)
                            .single())
                    .isEqualTo(2598);
            assertThat(jdbc.sql("SELECT unit_price_minor FROM order_item")
                            .query(Long.class)
                            .single())
                    .isEqualTo(1299);
        }

        @Test
        void aPriceChangeInTheCatalogDoesNotRewriteExistingOrders() {
            UUID id = UUID.fromString(post("/api/v1/orders", customer1(), newKey(), orderBody("MUG-JAVA", 1))
                    .json()
                    .path("id")
                    .stringValue());
            jdbc.sql("UPDATE product SET price_minor = 9999 WHERE sku = 'MUG-JAVA'")
                    .update();
            try {
                assertThat(get("/api/v1/orders/" + id, customer1())
                                .json()
                                .path("total")
                                .path("amountMinor")
                                .longValue())
                        .isEqualTo(1299);
                assertThat(post("/api/v1/orders", customer1(), newKey(), orderBody("MUG-JAVA", 1))
                                .json()
                                .path("total")
                                .path("amountMinor")
                                .longValue())
                        .as("new orders use the new price")
                        .isEqualTo(9999);
            } finally {
                jdbc.sql("UPDATE product SET price_minor = 1299 WHERE sku = 'MUG-JAVA'")
                        .update();
            }
        }

        @Test
        void acceptsTheLimitsExactly() {
            List<String> skus = List.of("BOOK-CLEAN-CODE", "BOOK-DDIA", "MUG-JAVA", "STICKERS-PACK", "TSHIRT-OPP");
            // 20 lines need 20 distinct SKUs: add extra products to the catalog for this test
            IntStream.range(0, 15)
                    .forEach(i -> jdbc.sql(
                                    "INSERT INTO product (sku, name, price_minor, currency, active) VALUES (?, ?, 100, 'EUR', true)")
                            .params("EXTRA-" + i, "Extra " + i)
                            .update());
            try {
                List<String> all = new ArrayList<>(skus);
                IntStream.range(0, 15).forEach(i -> all.add("EXTRA-" + i));
                Object[] args =
                        all.stream().flatMap(sku -> Stream.of((Object) sku, 10)).toArray();

                Reply reply = post("/api/v1/orders", customer1(), newKey(), orderBody(args));

                assertThat(reply.status()).isEqualTo(201);
                assertThat(reply.json().path("items").size()).isEqualTo(20);
            } finally {
                jdbc.sql("DELETE FROM order_item").update();
                jdbc.sql("DELETE FROM product WHERE sku LIKE 'EXTRA-%'").update();
            }
        }

        // ---- 400: the request is malformed or breaks the limits ----

        static Stream<Arguments> invalidBodies() {
            String lines21 = IntStream.range(0, 21)
                    .mapToObj(i -> "{\"sku\":\"MUG-JAVA\",\"quantity\":1}")
                    .collect(java.util.stream.Collectors.joining(","));
            return Stream.of(
                    Arguments.of("no items property", "{}", "validation-failed", "items"),
                    Arguments.of("items null", "{\"items\":null}", "validation-failed", "items"),
                    Arguments.of("no lines", "{\"items\":[]}", "validation-failed", "items"),
                    Arguments.of("21 lines", "{\"items\":[" + lines21 + "]}", "validation-failed", "items"),
                    Arguments.of("a null line", "{\"items\":[null]}", "validation-failed", "items[0]"),
                    Arguments.of("quantity 0", orderBody("MUG-JAVA", 0), "validation-failed", "items[0].quantity"),
                    Arguments.of("quantity 11", orderBody("MUG-JAVA", 11), "validation-failed", "items[0].quantity"),
                    Arguments.of("quantity -1", orderBody("MUG-JAVA", -1), "validation-failed", "items[0].quantity"),
                    Arguments.of(
                            "no quantity",
                            "{\"items\":[{\"sku\":\"MUG-JAVA\"}]}",
                            "validation-failed",
                            "items[0].quantity"),
                    Arguments.of("no sku", "{\"items\":[{\"quantity\":1}]}", "validation-failed", "items[0].sku"),
                    Arguments.of(
                            "blank sku",
                            "{\"items\":[{\"sku\":\"  \",\"quantity\":1}]}",
                            "validation-failed",
                            "items[0].sku"),
                    Arguments.of(
                            "sku of 65 characters", orderBody("X".repeat(65), 1), "validation-failed", "items[0].sku"),
                    Arguments.of("duplicate sku", orderBody("MUG-JAVA", 1, "MUG-JAVA", 2), "invalid-order", null),
                    Arguments.of("quantity is a word", orderBody("MUG-JAVA", "\"two\""), "malformed-request", null),
                    Arguments.of("quantity is fractional", orderBody("MUG-JAVA", "1.5"), "malformed-request", null),
                    Arguments.of("items is not a list", "{\"items\":\"MUG-JAVA\"}", "malformed-request", null),
                    Arguments.of("not JSON", "this is not json", "malformed-request", null),
                    Arguments.of("truncated JSON", "{\"items\":[{\"sku\":", "malformed-request", null),
                    Arguments.of("a JSON array", "[]", "malformed-request", null),
                    Arguments.of("empty body", "", "malformed-request", null));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("invalidBodies")
        void rejectsAnInvalidRequestWithAProblemAndCreatesNothing(
                String name, String body, String expectedCode, String expectedField) {
            Reply reply = post("/api/v1/orders", customer1(), newKey(), body);

            assertThat(reply.status()).as(reply.body()).isEqualTo(400);
            assertThat(reply.header("Content-Type")).startsWith("application/problem+json");
            JsonNode problem = reply.json();
            assertThat(problem.path("type").stringValue()).isEqualTo("urn:problem-type:" + expectedCode);
            assertThat(problem.path("title").stringValue()).isEqualTo("Bad Request");
            assertThat(problem.path("status").intValue()).isEqualTo(400);
            assertThat(problem.path("detail").stringValue()).isNotBlank();
            assertThat(problem.path("instance").stringValue()).isEqualTo("/api/v1/orders");
            assertThat(reply.body()).doesNotContain("Exception").doesNotContain("com.altronixsoft");
            if (expectedField != null) {
                assertThat(problem.path("errors").findValuesAsString("field")).contains(expectedField);
                problem.path("errors")
                        .forEach(error ->
                                assertThat(error.path("message").stringValue()).isNotBlank());
            }
            assertThat(orderCount()).isZero();
        }

        @Test
        void theValidationProblemListsEveryViolation() {
            Reply reply = post(
                    "/api/v1/orders",
                    customer1(),
                    newKey(),
                    "{\"items\":[{\"sku\":\"\",\"quantity\":0},{\"sku\":\"MUG-JAVA\",\"quantity\":99}]}");

            assertThat(reply.json().path("errors").findValuesAsString("field"))
                    .contains("items[0].sku", "items[0].quantity", "items[1].quantity");
        }

        @ParameterizedTest
        @ValueSource(strings = {"text/plain", "application/xml", "application/x-www-form-urlencoded"})
        void aBodyThatIsNotJsonIsUnsupported(String contentType) {
            Reply reply = send(
                    "POST",
                    "/api/v1/orders",
                    customer1(),
                    "items=MUG-JAVA",
                    Map.of("Idempotency-Key", newKey(), "Content-Type", contentType));

            assertThat(reply.status()).as(reply.body()).isEqualTo(415);
            assertThat(reply.header("Content-Type")).startsWith("application/problem+json");
            assertThat(reply.problemType()).isEqualTo("urn:problem-type:unsupported-media-type");
            assertThat(orderCount()).isZero();
        }

        @Test
        void anUnknownSkuIsUnprocessableAndNamesTheSku() {
            Reply reply = post("/api/v1/orders", customer1(), newKey(), orderBody("MUG-JAVA", 1, "NO-SUCH-SKU", 1));

            assertThat(reply.status()).isEqualTo(422);
            assertThat(reply.header("Content-Type")).startsWith("application/problem+json");
            assertThat(reply.problemType()).isEqualTo("urn:problem-type:product-not-available");
            assertThat(reply.json().path("skus").findValuesAsString("")).isEmpty();
            assertThat(reply.json().path("skus").get(0).stringValue()).isEqualTo("NO-SUCH-SKU");
            assertThat(orderCount()).isZero();
        }

        @Test
        void aDiscontinuedSkuIsUnprocessable() {
            Reply reply = post("/api/v1/orders", customer1(), newKey(), orderBody("DISCONTINUED-MOUSE", 1));

            assertThat(reply.status()).isEqualTo(422);
            assertThat(reply.json().path("skus").get(0).stringValue()).isEqualTo("DISCONTINUED-MOUSE");
        }

        @Test
        void aMissingIdempotencyKeyIsRejectedBeforeAnythingHappens() {
            Reply reply = post("/api/v1/orders", customer1(), null, orderBody("MUG-JAVA", 1));

            assertThat(reply.status()).isEqualTo(400);
            assertThat(reply.header("Content-Type")).startsWith("application/problem+json");
            assertThat(orderCount()).isZero();
        }
    }

    // ------------------------------------------------------------------------------------------------------------

    @Nested
    class Idempotency {

        @Test
        void repeatingACreateReturnsTheSameOrderAndCreatesNoSecondOne() {
            String key = newKey();
            String body = orderBody("MUG-JAVA", 2);

            Reply first = post("/api/v1/orders", customer1(), key, body);
            Reply second = post("/api/v1/orders", customer1(), key, body);

            assertThat(first.status()).isEqualTo(201);
            assertThat(first.header("Idempotent-Replayed")).isNull();
            assertThat(second.status()).isEqualTo(201);
            assertThat(second.header("Idempotent-Replayed")).isEqualTo("true");
            assertThat(second.body()).isEqualTo(first.body());
            assertThat(second.header("Location")).isEqualTo(first.header("Location"));
            assertThat(orderCount()).isEqualTo(1);
        }

        @Test
        void aRetryWithTheFieldsInAnotherOrderIsTheSameRequest() {
            String key = newKey();

            Reply first =
                    post("/api/v1/orders", customer1(), key, "{\"items\":[{\"sku\":\"MUG-JAVA\",\"quantity\":1}]}");
            Reply second = post(
                    "/api/v1/orders",
                    customer1(),
                    key,
                    "{ \"items\" : [ { \"quantity\" : 1, \"sku\" : \"MUG-JAVA\" } ] }");

            assertThat(second.header("Idempotent-Replayed")).isEqualTo("true");
            assertThat(second.body()).isEqualTo(first.body());
            assertThat(orderCount()).isEqualTo(1);
        }

        @Test
        void theSameKeyWithAnotherBodyIsRejected() {
            String key = newKey();
            post("/api/v1/orders", customer1(), key, orderBody("MUG-JAVA", 1));

            Reply reply = post("/api/v1/orders", customer1(), key, orderBody("MUG-JAVA", 2));

            assertThat(reply.status()).isEqualTo(422);
            assertThat(reply.header("Content-Type")).startsWith("application/problem+json");
            assertThat(orderCount()).isEqualTo(1);
        }

        @Test
        void twoCustomersCanUseTheSameKeyWithoutSeeingEachOther() {
            String key = newKey();
            String body = orderBody("MUG-JAVA", 1);

            Reply one = post("/api/v1/orders", customer1(), key, body);
            Reply two = post("/api/v1/orders", customer2(), key, body);

            assertThat(two.status()).isEqualTo(201);
            assertThat(two.header("Idempotent-Replayed")).isNull();
            assertThat(two.json().path("id").stringValue())
                    .isNotEqualTo(one.json().path("id").stringValue());
            assertThat(two.json().path("customerId").stringValue()).isEqualTo(CUSTOMER2);
            assertThat(orderCount()).isEqualTo(2);
        }

        @Test
        void aKeyIsPerOperationNotPerEndpointName() {
            String key = newKey();
            post("/api/v1/orders", customer1(), key, orderBody("MUG-JAVA", 1));
            UUID other = seedOrder(CUSTOMER1);

            Reply cancel = post("/api/v1/orders/" + other + "/cancel", customer1(), key, null);

            assertThat(cancel.status())
                    .as("same key on another request is a reuse")
                    .isEqualTo(422);
            assertThat(statusOf(other)).isEqualTo(OrderStatus.PENDING_PAYMENT);
        }

        @Test
        void concurrentIdenticalCreatesRunTheBusinessMethodOnce() throws Exception {
            String key = newKey();
            String body = orderBody("MUG-JAVA", 1);
            int clients = 8;
            CountDownLatch go = new CountDownLatch(1);
            List<CompletableFuture<Reply>> replies = IntStream.range(0, clients)
                    .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                        await(go);
                        return post("/api/v1/orders", customer1(), key, body);
                    }))
                    .toList();
            go.countDown();

            List<Reply> all = replies.stream().map(CompletableFuture::join).toList();

            assertThat(orderCount()).isEqualTo(1);
            assertThat(all).allSatisfy(r -> assertThat(r.status()).isIn(201, 409));
            assertThat(all.stream()
                            .filter(r -> r.status() == 201)
                            .map(r -> r.json().path("id").stringValue())
                            .distinct())
                    .hasSize(1);
            assertThat(all.stream().filter(r -> r.status() == 409))
                    .allSatisfy(r -> assertThat(r.header("Retry-After")).isNotNull());
        }

        @Test
        void repeatingACancelReplaysTheAnswerInsteadOfFailing() {
            UUID id = seedOrder(CUSTOMER1);
            String key = newKey();

            Reply first = post("/api/v1/orders/" + id + "/cancel", customer1(), key, null);
            Reply second = post("/api/v1/orders/" + id + "/cancel", customer1(), key, null);
            Reply newKeyAfterwards = post("/api/v1/orders/" + id + "/cancel", customer1(), newKey(), null);

            assertThat(first.status()).isEqualTo(200);
            assertThat(second.status()).isEqualTo(200);
            assertThat(second.header("Idempotent-Replayed")).isEqualTo("true");
            assertThat(second.body()).isEqualTo(first.body());
            assertThat(newKeyAfterwards.status())
                    .as("a genuinely new request meets the new state")
                    .isEqualTo(409);
        }

        @Test
        void repeatingARefundReplaysTheAnswerAndRequestsOnlyOneRefund() {
            UUID id = seedOrder(CUSTOMER1, OrderStatus.PAID);
            String key = newKey();

            Reply first = post("/api/v1/orders/" + id + "/refund", admin(), key, null);
            Reply second = post("/api/v1/orders/" + id + "/refund", admin(), key, null);

            assertThat(first.status()).isEqualTo(202);
            assertThat(second.status()).isEqualTo(202);
            assertThat(second.header("Idempotent-Replayed")).isEqualTo("true");
            assertThat(jdbc.sql(
                                    "SELECT count(*) FROM order_status_history WHERE order_id = ? AND to_status = 'REFUND_REQUESTED'")
                            .param(id)
                            .query(Integer.class)
                            .single())
                    .isEqualTo(1);
        }

        @Test
        void aRefusedRequestIsReplayedToo() {
            UUID foreign = seedOrder(CUSTOMER2);
            String key = newKey();

            Reply first = post("/api/v1/orders/" + foreign + "/cancel", customer1(), key, null);
            Reply second = post("/api/v1/orders/" + foreign + "/cancel", customer1(), key, null);

            assertThat(first.status()).isEqualTo(404);
            assertThat(second.status()).isEqualTo(404);
            assertThat(second.header("Idempotent-Replayed")).isEqualTo("true");
        }

        private static void await(CountDownLatch latch) {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------

    @Nested
    class Read {

        @Test
        void theOwnerReadsTheirOrderWithItsHistory() {
            UUID id = seedOrder(CUSTOMER1, OrderStatus.CANCELLED);

            Reply reply = get("/api/v1/orders/" + id, customer1());

            assertThat(reply.status()).isEqualTo(200);
            JsonNode order = reply.json();
            assertThat(order.path("id").stringValue()).isEqualTo(id.toString());
            assertThat(order.path("status").stringValue()).isEqualTo("CANCELLED");
            assertThat(order.path("items").size()).isEqualTo(2);
            assertThat(order.path("history").findValuesAsString("to")).containsExactly("PENDING_PAYMENT", "CANCELLED");
            assertThat(order.path("history").get(1).path("from").stringValue()).isEqualTo("PENDING_PAYMENT");
            assertThat(order.path("history").get(1).path("reason").stringValue())
                    .isEqualTo("CUSTOMER");
            assertThat(order.path("createdAt").stringValue()).endsWith("Z");
            assertThat(reply.body()).doesNotContain("version").doesNotContain("sourceEventId");
        }

        @Test
        void anAdministratorReadsAnyOrder() {
            UUID id = seedOrder(CUSTOMER2);

            Reply reply = get("/api/v1/orders/" + id, admin());

            assertThat(reply.status()).isEqualTo(200);
            assertThat(reply.json().path("customerId").stringValue()).isEqualTo(CUSTOMER2);
        }

        @Test
        void someoneElsesOrderIsIndistinguishableFromAMissingOne() {
            UUID foreign = seedOrder(CUSTOMER2);
            UUID missing = UUID.randomUUID();

            Reply foreignReply = get("/api/v1/orders/" + foreign, customer1());
            Reply missingReply = get("/api/v1/orders/" + missing, customer1());

            assertThat(foreignReply.status()).isEqualTo(404);
            assertThat(missingReply.status()).isEqualTo(404);
            assertThat(foreignReply.body().replace(foreign.toString(), "ID"))
                    .isEqualTo(missingReply.body().replace(missing.toString(), "ID"));
            assertThat(foreignReply.header("Content-Type")).isEqualTo(missingReply.header("Content-Type"));
            assertThat(foreignReply.json().path("type").stringValue()).isEqualTo("urn:problem-type:order-not-found");
        }

        @ParameterizedTest
        @ValueSource(strings = {"not-a-uuid", "123", "00000000-0000-0000-0000-00000000000g"})
        void anIdThatIsNoUuidIsABadRequest(String id) {
            Reply reply = get("/api/v1/orders/" + id, customer1());

            assertThat(reply.status()).isEqualTo(400);
            assertThat(reply.problemType()).isEqualTo("urn:problem-type:validation-failed");
            assertThat(reply.json().path("errors").get(0).path("field").stringValue())
                    .isEqualTo("id");
        }
    }

    // ------------------------------------------------------------------------------------------------------------

    @Nested
    class ListOrders {

        @Test
        void aCustomerSeesOnlyTheirOwnOrdersNewestFirst() throws Exception {
            UUID first = seedOrder(CUSTOMER1);
            seedOrder(CUSTOMER2);
            Thread.sleep(5);
            UUID second = seedOrder(CUSTOMER1);

            Reply reply = get("/api/v1/orders", customer1());

            assertThat(reply.status()).isEqualTo(200);
            JsonNode page = reply.json();
            assertThat(page.path("content").findValuesAsString("id"))
                    .containsExactly(second.toString(), first.toString());
            assertThat(page.path("content").findValuesAsString("customerId")).containsOnly(CUSTOMER1);
            assertThat(page.path("page").intValue()).isZero();
            assertThat(page.path("size").intValue()).isEqualTo(20);
            assertThat(page.path("totalElements").longValue()).isEqualTo(2);
            assertThat(page.path("totalPages").intValue()).isEqualTo(1);
            JsonNode summary = page.path("content").get(0);
            assertThat(summary.path("status").stringValue()).isEqualTo("PENDING_PAYMENT");
            assertThat(summary.path("total").path("amountMinor").longValue()).isEqualTo(3097);
            assertThat(summary.path("lineCount").intValue()).isEqualTo(2);
            assertThat(summary.has("items")).as("a list holds summaries").isFalse();
        }

        @Test
        void anAdministratorSeesEveryonesOrders() {
            seedOrder(CUSTOMER1);
            seedOrder(CUSTOMER2);

            JsonNode page = get("/api/v1/orders", admin()).json();

            assertThat(page.path("totalElements").longValue()).isEqualTo(2);
            assertThat(page.path("content").findValuesAsString("customerId"))
                    .containsExactlyInAnyOrder(CUSTOMER1, CUSTOMER2);
        }

        @Test
        void aCustomerWithoutOrdersGetsAnEmptyPage() {
            seedOrder(CUSTOMER2);

            JsonNode page = get("/api/v1/orders", customer1()).json();

            assertThat(page.path("content").size()).isZero();
            assertThat(page.path("totalElements").longValue()).isZero();
            assertThat(page.path("totalPages").intValue()).isZero();
        }

        @Test
        void pagesWalkThroughTheOrdersWithoutGapsOrRepeats() {
            List<UUID> created =
                    IntStream.range(0, 5).mapToObj(i -> seedOrder(CUSTOMER1)).toList();

            List<String> seen = new ArrayList<>();
            for (int page = 0; page < 3; page++) {
                JsonNode result = get("/api/v1/orders?page=" + page + "&size=2", customer1())
                        .json();
                assertThat(result.path("page").intValue()).isEqualTo(page);
                assertThat(result.path("size").intValue()).isEqualTo(2);
                assertThat(result.path("totalElements").longValue()).isEqualTo(5);
                assertThat(result.path("totalPages").intValue()).isEqualTo(3);
                seen.addAll(result.path("content").findValuesAsString("id"));
            }

            assertThat(seen).hasSize(5).doesNotHaveDuplicates();
            assertThat(seen)
                    .containsExactlyInAnyOrderElementsOf(
                            created.stream().map(UUID::toString).toList());
            assertThat(get("/api/v1/orders?page=3&size=2", customer1())
                            .json()
                            .path("content")
                            .size())
                    .isZero();
        }

        @Test
        void theNewestOrderComesFirstEvenWhenCreatedInTheSameInstant() {
            List<UUID> ids =
                    IntStream.range(0, 4).mapToObj(i -> seedOrder(CUSTOMER1)).toList();
            jdbc.sql("UPDATE orders SET created_at = TIMESTAMPTZ '2026-01-01 00:00:00+00'")
                    .update();

            List<String> listed =
                    get("/api/v1/orders", customer1()).json().path("content").findValuesAsString("id");

            assertThat(listed)
                    .as("ties are broken by id, descending")
                    .isSortedAccordingTo(java.util.Comparator.reverseOrder());
            assertThat(listed)
                    .containsExactlyInAnyOrderElementsOf(
                            ids.stream().map(UUID::toString).toList());
        }

        @Test
        void aPageOf100IsAllowed() {
            assertThat(get("/api/v1/orders?size=100", customer1()).status()).isEqualTo(200);
        }

        @ParameterizedTest
        @ValueSource(strings = {"size=101", "size=0", "size=-5", "page=-1", "page=abc", "size=ten", "size=1000000"})
        void pagingOutsideTheLimitsIsABadRequest(String query) {
            Reply reply = get("/api/v1/orders?" + query, customer1());

            assertThat(reply.status()).as(reply.body()).isEqualTo(400);
            assertThat(reply.header("Content-Type")).startsWith("application/problem+json");
            assertThat(reply.problemType()).isEqualTo("urn:problem-type:validation-failed");
            assertThat(reply.json().path("errors").get(0).path("field").stringValue())
                    .isEqualTo(query.substring(0, query.indexOf('=')));
        }
    }

    // ------------------------------------------------------------------------------------------------------------

    @Nested
    class Cancel {

        @Test
        void theOwnerCancelsAPendingOrder() {
            UUID id = seedOrder(CUSTOMER1);

            Reply reply = post("/api/v1/orders/" + id + "/cancel", customer1(), newKey(), null);

            assertThat(reply.status()).isEqualTo(200);
            assertThat(reply.json().path("status").stringValue()).isEqualTo("CANCELLED");
            assertThat(reply.json().path("history").findValuesAsString("to"))
                    .containsExactly("PENDING_PAYMENT", "CANCELLED");
            assertThat(statusOf(id)).isEqualTo(OrderStatus.CANCELLED);
            assertThat(jdbc.sql("SELECT cancel_reason FROM orders WHERE id = ?")
                            .param(id)
                            .query(String.class)
                            .single())
                    .isEqualTo("CUSTOMER");
            assertThat(jdbc.sql(
                                    "SELECT source FROM order_status_history WHERE order_id = ? AND to_status = 'CANCELLED'")
                            .param(id)
                            .query(String.class)
                            .single())
                    .isEqualTo("API");
        }

        @ParameterizedTest
        @ValueSource(strings = {"PAID", "CANCELLED", "REFUND_REQUESTED", "REFUNDED", "REFUND_FAILED"})
        void anOrderThatIsNotPendingCannotBeCancelled(String status) {
            UUID id = seedOrder(CUSTOMER1, OrderStatus.valueOf(status));
            String before = ordersSnapshot();

            Reply reply = post("/api/v1/orders/" + id + "/cancel", customer1(), newKey(), null);

            assertThat(reply.status()).isEqualTo(409);
            assertThat(reply.header("Content-Type")).startsWith("application/problem+json");
            assertThat(reply.problemType()).isEqualTo("urn:problem-type:order-state-conflict");
            assertThat(reply.json().path("currentStatus").stringValue()).isEqualTo(status);
            assertThat(ordersSnapshot()).isEqualTo(before);
        }

        @Test
        void aMissingOrderIsNotFound() {
            Reply reply = post("/api/v1/orders/" + UUID.randomUUID() + "/cancel", customer1(), newKey(), null);

            assertThat(reply.status()).isEqualTo(404);
        }
    }

    // ------------------------------------------------------------------------------------------------------------

    @Nested
    class Refund {

        @Test
        void anAdministratorRefundsAPaidOrder() {
            UUID id = seedOrder(CUSTOMER1, OrderStatus.PAID);

            Reply reply = post("/api/v1/orders/" + id + "/refund", admin(), newKey(), null);

            assertThat(reply.status()).isEqualTo(202);
            assertThat(reply.json().path("status").stringValue()).isEqualTo("REFUND_REQUESTED");
            assertThat(statusOf(id)).isEqualTo(OrderStatus.REFUND_REQUESTED);
            assertThat(jdbc.sql(
                                    "SELECT reason FROM order_status_history WHERE order_id = ? AND to_status = 'REFUND_REQUESTED'")
                            .param(id)
                            .query(String.class)
                            .single())
                    .isEqualTo("ADMIN");
        }

        @Test
        void aFailedRefundCanBeRetried() {
            UUID id = seedOrder(CUSTOMER1, OrderStatus.REFUND_FAILED);

            Reply reply = post("/api/v1/orders/" + id + "/refund", admin(), newKey(), null);

            assertThat(reply.status()).isEqualTo(202);
            assertThat(statusOf(id)).isEqualTo(OrderStatus.REFUND_REQUESTED);
        }

        @ParameterizedTest
        @ValueSource(strings = {"PENDING_PAYMENT", "CANCELLED", "REFUND_REQUESTED", "REFUNDED"})
        void onlyPaidOrFailedRefundOrdersCanBeRefunded(String status) {
            UUID id = seedOrder(CUSTOMER1, OrderStatus.valueOf(status));
            String before = ordersSnapshot();

            Reply reply = post("/api/v1/orders/" + id + "/refund", admin(), newKey(), null);

            assertThat(reply.status()).isEqualTo(409);
            assertThat(reply.problemType()).isEqualTo("urn:problem-type:order-state-conflict");
            assertThat(reply.json().path("currentStatus").stringValue()).isEqualTo(status);
            assertThat(ordersSnapshot()).isEqualTo(before);
        }

        @Test
        void aSecondRefundWithAnotherKeyMeetsTheNewState() {
            UUID id = seedOrder(CUSTOMER1, OrderStatus.PAID);
            post("/api/v1/orders/" + id + "/refund", admin(), newKey(), null);

            Reply again = post("/api/v1/orders/" + id + "/refund", admin(), newKey(), null);

            assertThat(again.status()).isEqualTo(409);
            assertThat(again.json().path("currentStatus").stringValue()).isEqualTo("REFUND_REQUESTED");
        }

        @Test
        void aMissingOrderIsNotFound() {
            Reply reply = post("/api/v1/orders/" + UUID.randomUUID() + "/refund", admin(), newKey(), null);

            assertThat(reply.status()).isEqualTo(404);
            assertThat(reply.problemType()).isEqualTo("urn:problem-type:order-not-found");
        }
    }

    // ------------------------------------------------------------------------------------------------------------

    @Nested
    class Errors {

        @Test
        void anUnknownPathIsAProblemToo() {
            Reply reply = get("/api/v1/nothing-here", customer1());

            assertThat(reply.status()).isEqualTo(404);
            assertThat(reply.header("Content-Type")).startsWith("application/problem+json");
            assertThat(reply.problemType()).as(reply.body()).isEqualTo("urn:problem-type:not-found");
            assertThat(reply.json().path("instance").stringValue()).isEqualTo("/api/v1/nothing-here");
        }

        @Test
        void aMethodThatTheResourceDoesNotSupportIsAProblemWithAnAllowHeader() {
            UUID id = seedOrder(CUSTOMER1);

            Reply reply = send("DELETE", "/api/v1/orders/" + id, admin(), null, Map.of());

            assertThat(reply.status()).isEqualTo(405);
            assertThat(reply.header("Content-Type")).startsWith("application/problem+json");
            assertThat(reply.problemType()).as(reply.body()).isEqualTo("urn:problem-type:method-not-allowed");
            assertThat(reply.header("Allow")).contains("GET");
            assertThat(statusOf(id)).isEqualTo(OrderStatus.PENDING_PAYMENT);
        }

        @Test
        void aFailureInsideTheServiceIsAnOpaqueProblem() {
            jdbc.sql("ALTER TABLE order_item RENAME TO order_item_broken").update();
            try {
                Reply reply = get("/api/v1/orders/" + seedOrderQuietly(), admin());

                assertThat(reply.status()).isEqualTo(500);
                assertThat(reply.header("Content-Type")).startsWith("application/problem+json");
                assertThat(reply.problemType()).isEqualTo("urn:problem-type:internal-error");
                assertThat(reply.body())
                        .doesNotContain("order_item")
                        .doesNotContain("SQL")
                        .doesNotContain("Exception")
                        .doesNotContain("hibernate");
            } finally {
                jdbc.sql("ALTER TABLE order_item_broken RENAME TO order_item").update();
            }
        }

        private UUID seedOrderQuietly() {
            // the order exists, its lines cannot be read: the read fails after the lookup
            UUID id = UUID.randomUUID();
            jdbc.sql(
                            "INSERT INTO orders (id, customer_id, status, currency, total_minor, disputed, created_at, updated_at, version) "
                                    + "VALUES (?, ?, 'PENDING_PAYMENT', 'EUR', 100, false, now(), now(), 0)")
                    .params(id, CUSTOMER1)
                    .update();
            return id;
        }
    }
}
