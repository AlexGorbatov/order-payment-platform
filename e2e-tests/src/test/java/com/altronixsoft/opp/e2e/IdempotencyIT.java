package com.altronixsoft.opp.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.altronixsoft.opp.e2e.support.Actor;
import com.altronixsoft.opp.e2e.support.TestClient;
import com.altronixsoft.opp.e2e.support.TestClient.Line;
import com.altronixsoft.opp.e2e.support.TestClient.Reply;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Architecture §7.3: one request, however often and however concurrently the client sends it. */
class IdempotencyIT extends E2eTest {

    @Test
    @DisplayName(
            "F16: twenty concurrent creates with one Idempotency-Key make one order, one payment, one PaymentIntent")
    void F16_twentyConcurrentCreatesWithOneKey_createOneOrder() throws Exception {
        int callers = 20;
        String key = UUID.randomUUID().toString();
        Instant firstRequest = Instant.now().minusMillis(100);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        List<Future<Reply>> replies = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                replies.add(pool.submit(() -> {
                    go.await();
                    return client.createOrder(Actor.CUSTOMER, key, TestClient.BASKET);
                }));
            }
            go.countDown();
            List<Reply> answers = new ArrayList<>();
            for (Future<Reply> reply : replies) {
                answers.add(reply.get());
            }

            // the winner created the order; the others either got its stored answer or were told to come back
            assertThat(answers).extracting(Reply::status).containsOnly(201, 409);
            List<Reply> created =
                    answers.stream().filter(r -> r.status() == 201).toList();
            assertThat(created).isNotEmpty();
            assertThat(created.stream()
                            .map(r -> r.json().get("id").stringValue())
                            .collect(Collectors.toSet()))
                    .hasSize(1);
            assertThat(created.stream().filter(r -> r.header("Idempotent-Replayed") == null))
                    .as("the one request that was really executed")
                    .hasSize(1);
            answers.stream().filter(r -> r.status() == 409).forEach(r -> {
                assertThat(r.header("Retry-After")).isNotNull();
                assertThat(r.json().get("type").stringValue()).contains("request-in-progress");
            });
        } finally {
            pool.shutdownNow();
        }

        // a later retry with the same key gets the same order back
        Reply later = client.createOrder(Actor.CUSTOMER, key, TestClient.BASKET);
        assertThat(later.status()).isEqualTo(201);
        assertThat(later.header("Idempotent-Replayed")).isEqualTo("true");
        Set<UUID> orders = client.orders();
        assertThat(orders).hasSize(1);
        UUID orderId = orders.iterator().next();
        assertThat(later.json().get("id").stringValue()).isEqualTo(orderId.toString());

        // and nothing was done twice downstream
        assertThat(platform.ordersDb()
                        .sql("select count(*) from orders where customer_id = :customer and created_at >= :since")
                        .param("customer", Actor.CUSTOMER.subject())
                        .param("since", Timestamp.from(firstRequest))
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        client.awaitPayable(orderId);
        assertThat(stripe.paymentIntentsOf(orderId)).hasSize(1);
    }

    @Test
    @DisplayName("F17: the same Idempotency-Key with a different request is refused, not mistaken for a retry")
    void F17_sameKeyDifferentBody_isRejected() {
        String key = UUID.randomUUID().toString();
        Reply first = client.createOrder(Actor.CUSTOMER, key, List.of(new Line("MUG-JAVA", 1)));
        assertThat(first.status()).isEqualTo(201);

        Reply other = client.createOrder(Actor.CUSTOMER, key, List.of(new Line("MUG-JAVA", 2)));
        assertThat(other.status()).isEqualTo(422);
        assertThat(client.orders()).hasSize(1);

        // the same key from another customer is another key
        Reply someoneElse = client.createOrder(Actor.OTHER_CUSTOMER, key, List.of(new Line("MUG-JAVA", 2)));
        assertThat(someoneElse.status()).isEqualTo(201);
        assertThat(client.orders()).hasSize(2);
    }
}
