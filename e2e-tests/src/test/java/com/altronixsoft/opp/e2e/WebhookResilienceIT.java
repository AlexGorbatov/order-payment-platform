package com.altronixsoft.opp.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.altronixsoft.opp.e2e.support.Actor;
import com.altronixsoft.opp.e2e.support.Outbox;
import com.altronixsoft.opp.e2e.support.StripeSimulator.PaymentIntentView;
import com.altronixsoft.opp.e2e.support.StripeSimulator.SimEvent;
import com.altronixsoft.opp.e2e.support.StripeSimulator.WebhookMode;
import com.altronixsoft.opp.e2e.support.TestClient;
import com.altronixsoft.opp.e2e.support.TestClient.Order;
import com.altronixsoft.opp.e2e.support.TestClient.Reply;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Architecture §6.6, §8.3, §8.4: Stripe's webhooks arrive twice, in the wrong order, or not at all. */
class WebhookResilienceIT extends E2eTest {

    @Test
    @DisplayName("F09: every kind of webhook delivered three times at once changes the state once and publishes once")
    void F09_duplicateWebhooksOfEveryType_haveTheEffectOnce() {
        stripe.webhookCopies(3);
        int deliveriesBefore = stripe.deliveryStatuses().size();

        // payment_intent.payment_failed, requires_action, succeeded
        Order attempts = client.placeOrder();
        client.awaitPayable(attempts.id());
        client.confirm(attempts.id(), "decline");
        TestClient.eventually(
                "the failed attempt",
                () -> assertThat(Outbox.count(platform.paymentsDb(), attempts.id(), "PaymentAttemptFailed"))
                        .isEqualTo(1));
        client.confirm(attempts.id(), "requires_3ds");
        client.awaitPayment(attempts.id(), "REQUIRES_ACTION");
        stripe.completeAuthentication(stripe.paymentIntentOf(attempts.id()).id(), true);
        client.awaitOrder(attempts.id(), "PAID");

        // charge.dispute.created, charge.refunded
        Order disputed = client.placeOrder();
        client.awaitPayable(disputed.id());
        client.confirm(disputed.id(), "dispute");
        client.awaitOrder(disputed.id(), "PAID");
        TestClient.eventually(
                "the dispute",
                () -> assertThat(client.order(Actor.ADMIN, disputed.id()).disputed())
                        .isTrue());
        client.refundOrder(Actor.ADMIN, disputed.id(), UUID.randomUUID().toString());
        client.awaitOrder(disputed.id(), "REFUNDED");

        // payment_intent.canceled
        Order cancelled = client.placeOrder();
        client.awaitPayable(cancelled.id());
        client.cancelOrder(Actor.CUSTOMER, cancelled.id());
        client.awaitPayment(cancelled.id(), "CANCELED");

        // refund.failed
        Order refundFails = client.placeOrder();
        client.awaitPayable(refundFails.id());
        client.confirm(refundFails.id(), "refund_fail");
        client.awaitOrder(refundFails.id(), "PAID");
        client.refundOrder(Actor.ADMIN, refundFails.id(), UUID.randomUUID().toString());
        TestClient.eventually(
                "the refund at Stripe",
                () -> assertThat(stripe.refundsOf(
                                stripe.paymentIntentOf(refundFails.id()).id()))
                        .hasSize(1));
        stripe.settleRefund(
                stripe.refundsOf(stripe.paymentIntentOf(refundFails.id()).id())
                        .get(0)
                        .id(),
                false);
        client.awaitOrder(refundFails.id(), "REFUND_FAILED");

        // one event each, however often Stripe said it
        assertThat(Outbox.types(platform.paymentsDb(), attempts.id()))
                .containsExactly(
                        "PaymentInitiated", "PaymentAttemptFailed", "PaymentActionRequired", "PaymentSucceeded");
        assertThat(Outbox.types(platform.paymentsDb(), disputed.id()))
                .containsExactlyInAnyOrder(
                        "PaymentInitiated", "PaymentSucceeded", "PaymentDisputed", "PaymentRefunded");
        assertThat(Outbox.types(platform.paymentsDb(), cancelled.id()))
                .containsExactly("PaymentInitiated", "PaymentCanceled");
        assertThat(Outbox.types(platform.paymentsDb(), refundFails.id()))
                .containsExactly("PaymentInitiated", "PaymentSucceeded", "PaymentRefundFailed");
        assertThat(Outbox.types(platform.ordersDb(), disputed.id()))
                .containsExactly("OrderCreated", "OrderRefundRequested");

        // every event is stored once and processed once; the duplicates were acknowledged, not refused
        List<SimEvent> emitted = new ArrayList<>();
        for (Order order : List.of(attempts, disputed, cancelled, refundFails)) {
            emitted.addAll(stripe.eventsOf(stripe.paymentIntentOf(order.id()).id()));
        }
        assertThat(emitted)
                .extracting(SimEvent::type)
                .contains(
                        "payment_intent.payment_failed",
                        "payment_intent.requires_action",
                        "payment_intent.succeeded",
                        "payment_intent.canceled",
                        "charge.dispute.created",
                        "charge.refunded",
                        "refund.failed");
        String ids = emitted.stream().map(e -> "'" + e.id() + "'").collect(Collectors.joining(","));
        Map<String, Long> stored = platform
                .paymentsDb()
                .sql("select status, count(*) as n from stripe_webhook_event where event_id in (" + ids
                        + ") group by status")
                .query((rs, row) -> Map.entry(rs.getString("status"), rs.getLong("n")))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertThat(stored).containsOnly(Map.entry("PROCESSED", (long) emitted.size()));
        List<Integer> statuses = stripe.deliveryStatuses()
                .subList(deliveriesBefore, stripe.deliveryStatuses().size());
        assertThat(statuses).hasSize(3 * emitted.size()).containsOnly(200);
    }

    @Test
    @DisplayName("F10: an older requires_action that arrives after the success is dropped as stale")
    void F10_olderWebhookArrivingAfterSuccess_isIgnored() {
        Order order = client.placeOrder();
        client.awaitPayable(order.id());
        String intentId = stripe.paymentIntentOf(order.id()).id();

        stripe.webhooks(WebhookMode.HOLD);
        client.confirm(order.id(), "requires_3ds");
        stripe.completeAuthentication(intentId, true);
        List<SimEvent> held = stripe.heldWebhooks();
        assertThat(held)
                .extracting(SimEvent::type)
                .containsExactly("payment_intent.requires_action", "payment_intent.succeeded");
        SimEvent requiresAction = held.get(0);
        SimEvent succeeded = held.get(1);
        assertThat(succeeded.created()).isAfter(requiresAction.created());

        stripe.webhooks(WebhookMode.AUTO);
        stripe.deliver(succeeded);
        client.awaitOrder(order.id(), "PAID");
        stripe.deliver(requiresAction);
        TestClient.eventually(
                "the stale event to be processed",
                () -> assertThat(webhookStatus(requiresAction)).isEqualTo("PROCESSED"));

        assertThat(client.payment(Actor.ADMIN, order.id()).orElseThrow().status())
                .isEqualTo("SUCCEEDED");
        assertThat(client.order(Actor.CUSTOMER, order.id()).status()).isEqualTo("PAID");
        assertThat(Outbox.types(platform.paymentsDb(), order.id()))
                .containsExactly("PaymentInitiated", "PaymentSucceeded");
    }

    @Test
    @DisplayName("F10: a payment_failed that Stripe sent before the success but delivered after it is dropped as stale")
    void F10_olderFailureArrivingAfterSuccess_isIgnored() {
        Order order = client.placeOrder();
        client.awaitPayable(order.id());

        stripe.webhooks(WebhookMode.HOLD);
        client.confirm(order.id(), "decline");
        client.confirm(order.id(), "success");
        List<SimEvent> held = stripe.heldWebhooks();
        assertThat(held)
                .extracting(SimEvent::type)
                .containsExactly("payment_intent.payment_failed", "payment_intent.succeeded");

        stripe.webhooks(WebhookMode.AUTO);
        stripe.deliver(held.get(1));
        client.awaitOrder(order.id(), "PAID");
        stripe.deliver(held.get(0));
        TestClient.eventually(
                "the stale event to be processed",
                () -> assertThat(webhookStatus(held.get(0))).isEqualTo("PROCESSED"));

        assertThat(client.payment(Actor.ADMIN, order.id()).orElseThrow().status())
                .isEqualTo("SUCCEEDED");
        assertThat(client.order(Actor.CUSTOMER, order.id()).status()).isEqualTo("PAID");
        assertThat(Outbox.types(platform.paymentsDb(), order.id()))
                .containsExactly("PaymentInitiated", "PaymentSucceeded");
    }

    @Test
    @DisplayName("F11/F21: a lost webhook is found by reconciliation; the late webhook then changes nothing")
    void F11_lostWebhook_isRecoveredByReconciliation() {
        Order order = client.placeOrder();
        TestClient.Payment payable = client.awaitPayable(order.id());
        PaymentIntentView intent = stripe.paymentIntentOf(order.id());

        // Stripe takes the money; the webhook never arrives
        stripe.webhooks(WebhookMode.DROP);
        client.confirm(order.id(), "success");
        SimEvent lost = stripe.eventsOf(intent.id()).get(0);
        assertThat(lost.type()).isEqualTo("payment_intent.succeeded");
        assertThat(client.order(Actor.CUSTOMER, order.id()).status()).isEqualTo("PENDING_PAYMENT");

        // the payment has to be quiet for a while before reconciliation looks at it
        TestClient.eventually("reconciliation to find the drift", () -> {
            Reply run = client.runReconciliation();
            assertThat(run.status()).as(run.body()).isEqualTo(200);
            JsonNode drifts = run.json().get("drifts");
            assertThat(drifts.size()).isPositive();
            boolean found = false;
            for (JsonNode drift : drifts) {
                if (drift.get("paymentId")
                        .stringValue()
                        .equals(payable.paymentId().toString())) {
                    assertThat(drift.get("from").stringValue()).isEqualTo("REQUIRES_PAYMENT_METHOD");
                    assertThat(drift.get("to").stringValue()).isEqualTo("SUCCEEDED");
                    found = true;
                }
            }
            assertThat(found)
                    .as("drift of payment %s in %s", payable.paymentId(), run.body())
                    .isTrue();
        });

        client.awaitOrder(order.id(), "PAID");
        assertThat(paymentHistory(payable.paymentId()))
                .last()
                .isEqualTo("REQUIRES_PAYMENT_METHOD->SUCCEEDED via RECONCILIATION");
        assertThat(Outbox.types(platform.paymentsDb(), order.id()))
                .containsExactly("PaymentInitiated", "PaymentSucceeded");

        // Stripe finally gets through: the webhook finds the work done
        stripe.webhooks(WebhookMode.AUTO);
        stripe.deliver(lost);
        TestClient.eventually(
                "the late webhook to be processed",
                () -> assertThat(webhookStatus(lost)).isEqualTo("PROCESSED"));
        assertThat(Outbox.count(platform.paymentsDb(), order.id(), "PaymentSucceeded"))
                .isEqualTo(1);
        assertThat(client.order(Actor.CUSTOMER, order.id()).status()).isEqualTo("PAID");
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private String webhookStatus(SimEvent event) {
        return platform.paymentsDb()
                .sql("select status from stripe_webhook_event where event_id = :id")
                .param("id", event.id())
                .query(String.class)
                .optional()
                .orElse("(not received)");
    }

    private List<String> paymentHistory(UUID paymentId) {
        return platform.paymentsDb()
                .sql("select coalesce(from_status, '-') || '->' || to_status || ' via ' || source "
                        + "from payment_status_history where payment_id = :id order by id")
                .param("id", paymentId)
                .query(String.class)
                .list();
    }
}
