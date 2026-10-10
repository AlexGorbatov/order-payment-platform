package com.altronixsoft.opp.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.altronixsoft.opp.contracts.PaymentSucceeded;
import com.altronixsoft.opp.contracts.Topics;
import com.altronixsoft.opp.e2e.support.Actor;
import com.altronixsoft.opp.e2e.support.Outbox;
import com.altronixsoft.opp.e2e.support.Platform;
import com.altronixsoft.opp.e2e.support.StripeSimulator.Operation;
import com.altronixsoft.opp.e2e.support.TestClient;
import com.altronixsoft.opp.e2e.support.TestClient.Order;
import com.altronixsoft.opp.e2e.support.TestClient.Reply;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Things that go wrong with the infrastructure and the processes themselves (architecture §15, F01, F05, F15). */
class ChaosIT extends E2eTest {

    @Test
    @DisplayName("F01: Kafka is paused while orders are created and cancelled - after unpause everything arrives once")
    void F01_kafkaPausedDuringOrderCreation_allDeliveredWithoutDuplicates() {
        int orderCount = 8;
        List<Order> orders = new ArrayList<>();
        Set<UUID> cancelled = new java.util.HashSet<>();

        platform.pauseKafka();
        try {
            for (int i = 0; i < orderCount; i++) {
                orders.add(client.placeOrder());
            }
            // half of the customers change their mind: OrderCancelled queues up behind OrderCreated of the same key
            for (int i = 0; i < orderCount; i += 2) {
                assertThat(client.cancelOrder(Actor.CUSTOMER, orders.get(i).id())
                                .status())
                        .isEqualTo(200);
                cancelled.add(orders.get(i).id());
            }

            // the orders are committed and their events wait in the outbox; nothing gets through
            long queued = orderCount + cancelled.size();
            TestClient.eventually(
                    "the events to pile up in the outbox",
                    () -> assertThat(unpublishedOrderEvents()).isGreaterThanOrEqualTo(queued));
            await().during(Duration.ofSeconds(3))
                    .atMost(Duration.ofSeconds(10))
                    .until(() -> paymentsExistFor(orders) == 0);
        } finally {
            platform.unpauseKafka();
        }

        // everything is delivered after the pause, and the cancellations come after the creations
        for (Order order : orders) {
            if (cancelled.contains(order.id())) {
                client.awaitPayment(order.id(), "CANCELED");
            } else {
                client.awaitPayable(order.id());
            }
        }
        for (Order order : orders) {
            if (cancelled.contains(order.id())) {
                continue;
            }
            client.confirm(order.id(), "success");
            client.awaitOrder(order.id(), "PAID");
        }

        // no duplicates: one payment per order, one PaymentIntent per paid order, every event handled once
        assertThat(paymentsExistFor(orders)).isEqualTo(orderCount);
        for (Order order : orders) {
            if (cancelled.contains(order.id())) {
                // Either the cancellation won the race and no PaymentIntent was ever made, or the worker was first and
                // the PaymentIntent was made and then cancelled: both are right, a live one or a second one is not.
                assertThat(stripe.paymentIntentsOf(order.id()))
                        .hasSizeLessThanOrEqualTo(1)
                        .allSatisfy(pi -> assertThat(pi.status()).isEqualTo("canceled"));
            } else {
                assertThat(stripe.paymentIntentsOf(order.id())).hasSize(1);
            }
            assertThat(Outbox.count(platform.ordersDb(), order.id(), "OrderCreated"))
                    .isEqualTo(1);
            assertThat(client.order(Actor.ADMIN, order.id()).status())
                    .isEqualTo(cancelled.contains(order.id()) ? "CANCELLED" : "PAID");
        }
        Set<UUID> sent = orders.stream()
                .flatMap(o -> platform
                        .ordersDb()
                        .sql("select id from outbox_event where partition_key = :key")
                        .param("key", o.id().toString())
                        .query(UUID.class)
                        .list()
                        .stream())
                .collect(Collectors.toSet());
        Set<UUID> handled = platform.paymentsDb()
                .sql("select event_id from inbox_message where consumer_group = 'payment-service'")
                .query(UUID.class)
                .set();
        assertThat(handled).containsAll(sent);
    }

    @Test
    @DisplayName("F04/F05: payment-service is killed in the middle of creating PaymentIntents - exactly one per order")
    void F05_paymentServiceKilledMidBatch_exactlyOnePaymentIntentPerOrder() {
        int orderCount = 6;
        // Stripe creates the PaymentIntent and then takes its time to say so: a crash in that second is the crash
        // between Stripe's answer and the local commit
        stripe.delayResponses(Operation.CREATE_PAYMENT_INTENT, Duration.ofSeconds(1));
        List<Order> orders = new ArrayList<>();
        for (int i = 0; i < orderCount; i++) {
            orders.add(client.placeOrder());
        }
        Set<UUID> ids = orders.stream().map(Order::id).collect(Collectors.toSet());

        TestClient.eventually(
                "the initiation batch to be under way",
                () -> assertThat(createCallsFor(ids)).isGreaterThanOrEqualTo(3));
        platform.paymentService().kill();

        // the crash left things half done: PaymentIntents exist at Stripe that no payment knows about
        long knownAtStripe = orders.stream()
                .filter(o -> !stripe.paymentIntentsOf(o.id()).isEmpty())
                .count();
        long initiatedLocally = platform.paymentsDb()
                .sql("select count(*) from payment where status <> 'CREATED' and order_id in (" + literals(ids) + ")")
                .query(Long.class)
                .single();
        assertThat(knownAtStripe).isGreaterThan(initiatedLocally);
        long repliesBefore = stripe.replays(Operation.CREATE_PAYMENT_INTENT);

        stripe.delayResponses(Operation.CREATE_PAYMENT_INTENT, Duration.ZERO);
        platform.paymentService().start(Platform.startTimeout());

        for (Order order : orders) {
            client.awaitPayable(order.id());
        }
        // the retry of the interrupted call carried the same key, so Stripe answered with the PaymentIntent it had made
        assertThat(stripe.replays(Operation.CREATE_PAYMENT_INTENT)).isGreaterThan(repliesBefore);
        for (Order order : orders) {
            assertThat(stripe.paymentIntentsOf(order.id()))
                    .as("PaymentIntents of %s", order.id())
                    .hasSize(1);
            assertThat(Outbox.count(platform.paymentsDb(), order.id(), "PaymentInitiated"))
                    .isEqualTo(1);
        }
        assertThat(paymentsExistFor(orders)).isEqualTo(orderCount);
        for (Order order : orders) {
            client.confirm(order.id(), "success");
            client.awaitOrder(order.id(), "PAID");
        }
    }

    @Test
    @DisplayName(
            "F15: a poison message goes to the dead-letter topic, is stored, does not block the consumer, and is resolved")
    void F15_garbageOnTheTopic_isDeadLetteredWithoutBlockingTheConsumer() {
        Order order = client.placeOrder();
        client.awaitPayable(order.id());

        // garbage on the very partition the order's real events will use
        kafka.send(Topics.PAYMENT_EVENTS, order.id().toString(), "{ this is not an event", Map.of());

        JsonNode deadLetter = awaitDeadLetter(platform.orderService(), order.id());
        assertThat(deadLetter.get("originalTopic").stringValue()).isEqualTo(Topics.PAYMENT_EVENTS);
        assertThat(deadLetter.get("status").stringValue()).isEqualTo("NEW");
        // only the consuming service keeps a dead letter; the other one has no business with it
        await().during(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(10))
                .until(() ->
                        deadLettersOf(platform.paymentService(), order.id()).isEmpty());

        // the consumer carries on: the real payment event behind the poison is applied
        client.confirm(order.id(), "success");
        client.awaitOrder(order.id(), "PAID");

        // a payload that is not an event cannot be replayed; the operator resolves it
        String id = deadLetter.get("id").stringValue();
        assertThat(client.replayDeadLetter(platform.orderService(), id).status())
                .isEqualTo(422);
        Reply resolved = client.resolveDeadLetter(platform.orderService(), id, "Producer bug: not an event envelope");
        assertThat(resolved.status()).isEqualTo(200);
        assertThat(resolved.json().get("status").stringValue()).isEqualTo("RESOLVED");
    }

    @Test
    @DisplayName("F15: an event the state cannot explain is dead-lettered; once the data is fixed, replay applies it")
    void F15_eventForUnknownOrder_isDeadLetteredThenReplayedAfterTheFix() {
        UUID orderId = UUID.randomUUID();
        client.adopt(orderId);
        kafka.publish(
                Topics.PAYMENT_EVENTS,
                new PaymentSucceeded(UUID.randomUUID(), orderId, 1299, "EUR", "pi_poison_" + orderId, Instant.now()));

        JsonNode deadLetter = awaitDeadLetter(platform.orderService(), orderId);
        assertThat(deadLetter.get("exceptionMessage").stringValue()).contains("unknown order");
        // not a retry storm: one dead letter for the one record
        assertThat(deadLettersOf(orderId)).hasSize(1);

        // the operator recreates the missing prerequisite (here: the order itself) and replays
        insertOrder(orderId);
        String id = deadLetter.get("id").stringValue();
        Reply replayed = client.replayDeadLetter(platform.orderService(), id);
        assertThat(replayed.status()).isEqualTo(200);
        assertThat(replayed.json().get("status").stringValue()).isEqualTo("REPLAYED");

        client.awaitOrder(orderId, "PAID");
        // once only
        assertThat(client.replayDeadLetter(platform.orderService(), id).status())
                .isEqualTo(409);
        // the replayed record was published again through the outbox with a marker of where it came from
        assertThat(platform.ordersDb()
                        .sql("select count(*) from outbox_event where headers->>'x-replay-of' = :id")
                        .param("id", id)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private JsonNode awaitDeadLetter(com.altronixsoft.opp.e2e.support.ServiceProcess service, UUID orderId) {
        return await("a dead letter for order " + orderId)
                .atMost(TestClient.ASYNC)
                .pollInterval(Duration.ofMillis(200))
                .until(() -> deadLettersOf(service, orderId), found -> !found.isEmpty())
                .get(0);
    }

    private List<JsonNode> deadLettersOf(UUID orderId) {
        return deadLettersOf(platform.orderService(), orderId);
    }

    private List<JsonNode> deadLettersOf(com.altronixsoft.opp.e2e.support.ServiceProcess service, UUID orderId) {
        List<JsonNode> found = new ArrayList<>();
        for (String status : List.of("NEW", "REPLAYED")) {
            for (JsonNode letter : client.deadLetters(service, status)) {
                if (orderId.toString().equals(letter.path("messageKey").asString(""))) {
                    found.add(letter);
                }
            }
        }
        return found;
    }

    private void insertOrder(UUID orderId) {
        platform.ordersDb()
                .sql(
                        "insert into orders (id, customer_id, status, currency, total_minor, created_at, updated_at, version) "
                                + "values (:id, :customer, 'PENDING_PAYMENT', 'EUR', 1299, now(), now(), 0)")
                .param("id", orderId)
                .param("customer", Actor.CUSTOMER.subject())
                .update();
        platform.ordersDb()
                .sql("insert into order_item (order_id, sku, name, quantity, unit_price_minor, line_total_minor) "
                        + "values (:id, 'MUG-JAVA', 'Coffee mug \"Java\"', 1, 1299, 1299)")
                .param("id", orderId)
                .update();
        platform.ordersDb()
                .sql("insert into order_status_history (order_id, from_status, to_status, reason, source, occurred_at) "
                        + "values (:id, null, 'PENDING_PAYMENT', 'restored by an operator', 'API', now())")
                .param("id", orderId)
                .update();
    }

    private long unpublishedOrderEvents() {
        return platform.ordersDb()
                .sql("select count(*) from outbox_event where published_at is null")
                .query(Long.class)
                .single();
    }

    private long paymentsExistFor(List<Order> orders) {
        return platform.paymentsDb()
                .sql("select count(*) from payment where order_id in ("
                        + literals(orders.stream().map(Order::id).collect(Collectors.toSet())) + ")")
                .query(Long.class)
                .single();
    }

    private long createCallsFor(Set<UUID> orderIds) {
        return orderIds.stream()
                .mapToLong(
                        id -> stripe.calls(Operation.CREATE_PAYMENT_INTENT, id).size())
                .sum();
    }

    private static String literals(Set<UUID> ids) {
        return ids.stream().map(id -> "'" + id + "'").collect(Collectors.joining(","));
    }
}
