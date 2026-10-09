package com.altronixsoft.opp.e2e.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.altronixsoft.opp.e2e.support.StripeSimulator.ApiCall;
import com.altronixsoft.opp.e2e.support.StripeSimulator.Operation;
import com.altronixsoft.opp.e2e.support.StripeSimulator.PaymentIntentView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.assertj.core.api.SoftAssertions;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The global assertions of architecture §14, checked at the end of every scenario once the platform has gone quiet:
 *
 * <ol>
 *   <li>at most one PaymentIntent per order, and never two succeeded ones;
 *   <li>the amount of every payment (and of its PaymentIntent) is the order's total;
 *   <li>no outbox row left unpublished, in either service;
 *   <li>no webhook event {@code DEAD}, none stuck {@code RECEIVED} or {@code FAILED}, no dead letter nobody dealt with;
 *   <li>no money returned twice: per PaymentIntent at most one refund that has not failed, never more than was paid;
 *   <li>every mutating call to Stripe carried an idempotency key derived from local ids (the safety rule of the project);
 *   <li>order and payment agree: a paid order has a succeeded payment, a refunded one a refunded payment, and so on.
 * </ol>
 *
 * "Quiet" matters: the platform is asynchronous, so the checks that look at the rest of the system wait for it to settle
 * (up to {@link TestClient#ASYNC}) before they judge. Rows of earlier scenarios are not looked at again: the global checks
 * cover the time since {@code since}, the per-order checks the orders given.
 */
public final class Invariants {

    private static final List<String> IDEMPOTENCY_KEY_PREFIXES =
            List.of("pi-create:", "pi-cancel:", "pi-confirm:", "refund:");

    private final Platform platform;
    /** Every order the scenario cares about; the ones it placed through the API and the ones it injected. */
    private final Set<UUID> orderIds;
    /** The orders placed through the API: each of them must end up with a payment. */
    private final Set<UUID> placed;

    private final Instant since;

    private Invariants(Platform platform, Set<UUID> placed, Set<UUID> adopted, Instant since) {
        this.platform = platform;
        this.placed = placed;
        this.orderIds = new java.util.HashSet<>(placed);
        this.orderIds.addAll(adopted);
        this.since = since;
    }

    /** Waits for the platform to settle, then asserts everything; all violations are reported together. */
    public static void assertHold(Platform platform, Set<UUID> placed, Set<UUID> adopted, Instant since) {
        new Invariants(platform, placed, adopted, since).run();
    }

    private void run() {
        awaitQuiet();
        TestClient.eventually("order and payment states to agree", this::statesAgree);
        SoftAssertions soft = new SoftAssertions();
        onePaymentIntentPerOrder(soft);
        amountsMatch(soft);
        nothingUnexpectedLeftBehind(soft);
        noMoneyReturnedTwice(soft);
        everyMutatingCallHadAnIdempotencyKey(soft);
        soft.assertAll();
    }

    // ---------------------------------------------------------------------------------------------- quiescence

    private void awaitQuiet() {
        assertThat(platform.stripe().awaitWebhooksDelivered(TestClient.ASYNC))
                .as("webhooks the simulator still tried to deliver")
                .isTrue();
        TestClient.eventually("outboxes to be drained and webhook events processed", () -> {
            assertThat(count(platform.ordersDb(), "select count(*) from outbox_event where published_at is null"))
                    .as("unpublished outbox rows of order-service")
                    .isZero();
            assertThat(count(platform.paymentsDb(), "select count(*) from outbox_event where published_at is null"))
                    .as("unpublished outbox rows of payment-service")
                    .isZero();
            assertThat(count(
                            platform.paymentsDb(),
                            "select count(*) from stripe_webhook_event where status in ('RECEIVED', 'FAILED')"))
                    .as("webhook events not processed yet")
                    .isZero();
            // the work queues of payment-service have drained: every placed order has its payment, nobody waits for
            // a PaymentIntent, nobody waits for a refund to be sent
            if (!placed.isEmpty()) {
                String ids = literals(placed);
                assertThat(count(platform.paymentsDb(), "select count(*) from payment where order_id in (" + ids + ")"))
                        .as("payments of the %s placed orders", placed.size())
                        .isEqualTo(placed.size());
                assertThat(count(
                                platform.paymentsDb(),
                                "select count(*) from payment where status = 'CREATED' and order_id in (" + ids + ")"))
                        .as("payments still waiting for their PaymentIntent")
                        .isZero();
            }
            assertThat(count(platform.paymentsDb(), "select count(*) from refund where status = 'REQUESTED'"))
                    .as("refunds not sent to Stripe yet")
                    .isZero();
        });
    }

    // ---------------------------------------------------------------------------------------------- the checks

    private void statesAgree() {
        SoftAssertions soft = new SoftAssertions();
        Map<UUID, OrderRow> orders = orders();
        Map<UUID, PaymentRow> payments = payments();
        orders.forEach((orderId, order) -> {
            PaymentRow payment = payments.get(orderId);
            if (payment == null) {
                return;
            }
            String where = "order " + orderId + " is " + order.status() + ", its payment " + payment.status();
            switch (order.status()) {
                case "PAID" -> soft.assertThat(payment.status()).as(where).isEqualTo("SUCCEEDED");
                case "REFUNDED" -> soft.assertThat(payment.status()).as(where).isEqualTo("REFUNDED");
                case "CANCELLED" -> soft.assertThat(payment.status()).as(where).isIn("CANCELED", "INITIATION_FAILED");
                case "PENDING_PAYMENT" ->
                    soft.assertThat(payment.status())
                            .as(where)
                            .isIn("CREATED", "REQUIRES_PAYMENT_METHOD", "REQUIRES_ACTION", "PROCESSING");
                default -> {
                    // REFUND_REQUESTED, REFUND_FAILED: the payment is paid (or already refunded) at that point
                    soft.assertThat(payment.status()).as(where).isIn("SUCCEEDED", "REFUNDED");
                }
            }
        });
        payments.forEach((orderId, payment) -> {
            OrderRow order = orders.get(orderId);
            if (order != null && payment.status().equals("REFUNDED")) {
                soft.assertThat(order.status())
                        .as("order of the refunded payment " + payment.id())
                        .isEqualTo("REFUNDED");
            }
        });
        soft.assertAll();
    }

    private void onePaymentIntentPerOrder(SoftAssertions soft) {
        for (UUID orderId : orderIds) {
            List<PaymentIntentView> intents = platform.stripe().paymentIntentsOf(orderId);
            soft.assertThat(intents).as("PaymentIntents of order %s", orderId).hasSizeLessThanOrEqualTo(1);
            soft.assertThat(intents.stream()
                            .filter(pi -> pi.status().equals("succeeded"))
                            .count())
                    .as("succeeded PaymentIntents of order %s", orderId)
                    .isLessThanOrEqualTo(1);
        }
    }

    private void amountsMatch(SoftAssertions soft) {
        Map<UUID, OrderRow> orders = orders();
        payments().forEach((orderId, payment) -> {
            OrderRow order = orders.get(orderId);
            soft.assertThat(order).as("order of payment %s", payment.id()).isNotNull();
            if (order == null) {
                return;
            }
            soft.assertThat(payment.amountMinor())
                    .as("amount of payment %s", payment.id())
                    .isEqualTo(order.totalMinor());
            soft.assertThat(payment.currency())
                    .as("currency of payment %s", payment.id())
                    .isEqualTo(order.currency());
            for (PaymentIntentView intent : platform.stripe().paymentIntentsOf(orderId)) {
                soft.assertThat(intent.amountMinor())
                        .as("amount of PaymentIntent %s", intent.id())
                        .isEqualTo(order.totalMinor());
                soft.assertThat(payment.stripePaymentIntentId())
                        .as("PaymentIntent recorded for payment %s", payment.id())
                        .isEqualTo(intent.id());
            }
        });
    }

    private void nothingUnexpectedLeftBehind(SoftAssertions soft) {
        soft.assertThat(count(platform.paymentsDb(), "select count(*) from stripe_webhook_event where status = 'DEAD'"))
                .as("DEAD webhook events")
                .isZero();
        for (JdbcClient db : List.of(platform.ordersDb(), platform.paymentsDb())) {
            soft.assertThat(db.sql(
                                    "select count(*) from dead_letter_message where status = 'NEW' and created_at >= :since")
                            .param("since", java.sql.Timestamp.from(since))
                            .query(Long.class)
                            .single())
                    .as("dead letters nobody dealt with")
                    .isZero();
        }
    }

    private void noMoneyReturnedTwice(SoftAssertions soft) {
        for (PaymentIntentView intent : platform.stripe().allPaymentIntents()) {
            if (!orderIds.contains(intent.orderId())) {
                continue;
            }
            var refunds = platform.stripe().refundsOf(intent.id());
            soft.assertThat(refunds.stream()
                            .filter(r -> !r.status().equals("failed"))
                            .count())
                    .as("refunds of %s that did not fail", intent.id())
                    .isLessThanOrEqualTo(1);
            soft.assertThat(intent.amountRefundedMinor())
                    .as("amount refunded of %s", intent.id())
                    .isLessThanOrEqualTo(intent.amountMinor());
        }
        List<Long> openRefundsPerPayment = platform.paymentsDb()
                .sql("select count(*) from refund where status <> 'FAILED' group by payment_id")
                .query(Long.class)
                .list();
        soft.assertThat(openRefundsPerPayment)
                .as("refunds per payment that did not fail")
                .allMatch(n -> n <= 1);
    }

    private void everyMutatingCallHadAnIdempotencyKey(SoftAssertions soft) {
        for (ApiCall call : platform.stripe().calls()) {
            if (call.operation() == Operation.RETRIEVE_PAYMENT_INTENT) {
                continue;
            }
            soft.assertThat(call.idempotencyKey())
                    .as("Idempotency-Key of %s %s", call.operation(), call.path())
                    .isNotBlank()
                    .satisfies(
                            key -> assertThat(IDEMPOTENCY_KEY_PREFIXES.stream().anyMatch(key::startsWith))
                                    .as("key %s derived from a local id", key)
                                    .isTrue());
        }
    }

    // ---------------------------------------------------------------------------------------------- data

    private record OrderRow(String status, long totalMinor, String currency) {}

    private record PaymentRow(
            UUID id, String status, long amountMinor, String currency, String stripePaymentIntentId) {}

    private Map<UUID, OrderRow> orders() {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        return platform
                .ordersDb()
                .sql("select id, status, total_minor, currency from orders where id in (" + literals() + ")")
                .query((rs, n) -> Map.entry(
                        rs.getObject("id", UUID.class),
                        new OrderRow(rs.getString("status"), rs.getLong("total_minor"), rs.getString("currency"))))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private Map<UUID, PaymentRow> payments() {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        return platform
                .paymentsDb()
                .sql("select id, order_id, status, amount_minor, currency, stripe_payment_intent_id from payment "
                        + "where order_id in (" + literals() + ")")
                .query((rs, n) -> Map.entry(
                        rs.getObject("order_id", UUID.class),
                        new PaymentRow(
                                rs.getObject("id", UUID.class),
                                rs.getString("status"),
                                rs.getLong("amount_minor"),
                                rs.getString("currency"),
                                rs.getString("stripe_payment_intent_id"))))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** UUIDs only (they come from the API), so they can be written into the statement. */
    private String literals() {
        return literals(orderIds);
    }

    private static String literals(Set<UUID> ids) {
        return ids.stream().map(id -> "'" + id + "'").collect(Collectors.joining(","));
    }

    private static long count(JdbcClient db, String sql) {
        return db.sql(sql).query(Long.class).single();
    }
}
