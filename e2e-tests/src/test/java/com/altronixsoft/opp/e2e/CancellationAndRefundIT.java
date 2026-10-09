package com.altronixsoft.opp.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.altronixsoft.opp.e2e.support.Actor;
import com.altronixsoft.opp.e2e.support.Outbox;
import com.altronixsoft.opp.e2e.support.StripeSimulator.Operation;
import com.altronixsoft.opp.e2e.support.StripeSimulator.PaymentIntentView;
import com.altronixsoft.opp.e2e.support.StripeSimulator.RefundView;
import com.altronixsoft.opp.e2e.support.StripeSimulator.WebhookMode;
import com.altronixsoft.opp.e2e.support.TestClient;
import com.altronixsoft.opp.e2e.support.TestClient.Order;
import com.altronixsoft.opp.e2e.support.TestClient.Payment;
import com.altronixsoft.opp.e2e.support.TestClient.Reply;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Architecture §6.4 and §6.5: cancellation by the customer or the timeout, the late payment, and refunds. */
class CancellationAndRefundIT extends E2eTest {

    @Test
    @DisplayName("Flow 6.4: an unpaid order past its payment timeout is cancelled and the PaymentIntent with it")
    void flow_6_4_paymentTimeoutCancelsOrderAndPaymentIntent() {
        Order order = client.placeOrder();
        Payment payable = client.awaitPayable(order.id());

        makeOverdue(order.id());

        client.awaitOrder(order.id(), "CANCELLED");
        assertThat(lastHistoryReason(client.order(Actor.CUSTOMER, order.id()))).isEqualTo("TIMEOUT");
        client.awaitPayment(order.id(), "CANCELED");
        assertThat(stripe.paymentIntentOf(order.id()).status()).isEqualTo("canceled");
        assertThat(stripe.calls(Operation.CANCEL_PAYMENT_INTENT, order.id()))
                .singleElement()
                .satisfies(call -> assertThat(call.idempotencyKey()).isEqualTo("pi-cancel:" + payable.paymentId()));

        // the echo of Stripe's cancellation changes nothing: one OrderCancelled, one PaymentCanceled
        assertThat(Outbox.types(platform.ordersDb(), order.id())).containsExactly("OrderCreated", "OrderCancelled");
        assertThat(Outbox.types(platform.paymentsDb(), order.id()))
                .containsExactly("PaymentInitiated", "PaymentCanceled");
    }

    @Test
    @DisplayName("Flow 6.4: a customer can cancel an unpaid order, and cannot cancel somebody else's")
    void flow_6_4_customerCancelsUnpaidOrder() {
        Order order = client.placeOrder();
        client.awaitPayable(order.id());

        assertThat(client.cancelOrder(Actor.OTHER_CUSTOMER, order.id()).status())
                .isEqualTo(404);
        Reply cancelled = client.cancelOrder(Actor.CUSTOMER, order.id());
        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.json().get("status").stringValue()).isEqualTo("CANCELLED");

        client.awaitPayment(order.id(), "CANCELED");
        assertThat(stripe.paymentIntentOf(order.id()).status()).isEqualTo("canceled");
        assertThat(Outbox.payloadField(platform.ordersDb(), order.id(), "OrderCancelled", "reason"))
                .isEqualTo("CUSTOMER");
    }

    @Test
    @DisplayName(
            "F18/F19: the customer pays while cancelling - Stripe refuses the cancel, the late payment is refunded")
    void F18_F19_paymentSucceedsAfterCustomerCancel_isRefundedAutomatically() {
        latePaymentAfterCancel(order -> assertThat(
                        client.cancelOrder(Actor.CUSTOMER, order.id()).status())
                .isEqualTo(200));
    }

    @Test
    @DisplayName("F18/F19: the customer pays after the payment timeout cancelled the order - the payment is refunded")
    void F18_F19_paymentSucceedsAfterTimeout_isRefundedAutomatically() {
        latePaymentAfterCancel(order -> {
            makeOverdue(order.id());
            client.awaitOrder(order.id(), "CANCELLED");
        });
    }

    /**
     * The money is taken at Stripe, the webhook is on its way (held), and the order is cancelled before it arrives.
     * payment-service asks Stripe to cancel the PaymentIntent and is told it is too late (F19, not retried); then the
     * webhook arrives, and order-service refunds the payment of the cancelled order (F18).
     */
    private void latePaymentAfterCancel(java.util.function.Consumer<Order> cancel) {
        Order order = client.placeOrder();
        Payment payable = client.awaitPayable(order.id());
        PaymentIntentView intent = stripe.paymentIntentOf(order.id());

        stripe.webhooks(WebhookMode.HOLD);
        client.confirm(order.id(), "success");
        assertThat(stripe.heldWebhooks()).extracting("type").containsExactly("payment_intent.succeeded");
        assertThat(client.payment(Actor.ADMIN, order.id()).orElseThrow().status())
                .isEqualTo("REQUIRES_PAYMENT_METHOD");

        cancel.accept(order);
        TestClient.eventually(
                "Stripe to be asked to cancel the PaymentIntent",
                () -> assertThat(stripe.calls(Operation.CANCEL_PAYMENT_INTENT, order.id()))
                        .hasSize(1));
        assertThat(stripe.calls(Operation.CANCEL_PAYMENT_INTENT, order.id())
                        .get(0)
                        .status())
                .isEqualTo(400);

        stripe.webhooks(WebhookMode.AUTO);
        stripe.releaseHeldWebhooks();

        client.awaitOrder(order.id(), "REFUNDED");
        client.awaitPayment(order.id(), "REFUNDED");
        assertThat(Outbox.payloadField(platform.ordersDb(), order.id(), "OrderRefundRequested", "reason"))
                .isEqualTo("LATE_PAYMENT_AFTER_CANCEL");
        assertThat(stripe.refundsOf(intent.id())).singleElement().satisfies(refund -> {
            assertThat(refund.status()).isEqualTo("succeeded");
            assertThat(refund.amountMinor()).isEqualTo(TestClient.BASKET_TOTAL_MINOR);
        });
        // refused with "unexpected state" once and never asked again
        assertThat(stripe.calls(Operation.CANCEL_PAYMENT_INTENT, order.id())).hasSize(1);
        assertThat(stripe.paymentIntentOf(order.id()).amountRefundedMinor()).isEqualTo(TestClient.BASKET_TOTAL_MINOR);
        assertThat(payable.stripePaymentIntentId()).isEqualTo(intent.id());
    }

    @Test
    @DisplayName("Flow 6.5: an admin refunds a paid order - one Stripe refund, order REFUNDED")
    void flow_6_5_adminRefund() {
        Order order = paidOrder("success");
        PaymentIntentView intent = stripe.paymentIntentOf(order.id());

        assertThat(client.refundOrder(
                                Actor.CUSTOMER, order.id(), UUID.randomUUID().toString())
                        .status())
                .isEqualTo(403);

        String key = UUID.randomUUID().toString();
        Reply requested = client.refundOrder(Actor.ADMIN, order.id(), key);
        assertThat(requested.status()).isEqualTo(202);
        assertThat(requested.json().get("status").stringValue()).isEqualTo("REFUND_REQUESTED");
        // the admin's client retries after a network hiccup: the same key returns the same answer, not a second refund
        Reply replay = client.refundOrder(Actor.ADMIN, order.id(), key);
        assertThat(replay.status()).isEqualTo(202);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");

        client.awaitOrder(order.id(), "REFUNDED");
        client.awaitPayment(order.id(), "REFUNDED");

        RefundView refund = stripe.refundsOf(intent.id()).get(0);
        assertThat(stripe.refundsOf(intent.id())).hasSize(1);
        assertThat(refund.status()).isEqualTo("succeeded");
        assertThat(refund.amountMinor()).isEqualTo(TestClient.BASKET_TOTAL_MINOR);
        assertThat(stripe.calls(Operation.CREATE_REFUND, order.id()))
                .singleElement()
                .satisfies(call -> assertThat(call.idempotencyKey()).isEqualTo("refund:" + refund.refundId()));
        assertThat(refundRowsOf(order.id())).containsExactly("SUCCEEDED");

        assertThat(Outbox.types(platform.ordersDb(), order.id()))
                .containsExactly("OrderCreated", "OrderRefundRequested");
        assertThat(Outbox.types(platform.paymentsDb(), order.id()))
                .containsExactly("PaymentInitiated", "PaymentSucceeded", "PaymentRefunded");

        // nothing more to refund
        assertThat(client.refundOrder(Actor.ADMIN, order.id(), UUID.randomUUID().toString())
                        .status())
                .isEqualTo(409);
    }

    @Test
    @DisplayName(
            "F20: the refund fails at the bank - REFUND_FAILED, then the admin retries with a new request and it works")
    void F20_refundFailsThenAdminRetries() {
        Order order = paidOrder("refund_fail");
        PaymentIntentView intent = stripe.paymentIntentOf(order.id());

        assertThat(client.refundOrder(Actor.ADMIN, order.id(), UUID.randomUUID().toString())
                        .status())
                .isEqualTo(202);
        TestClient.eventually(
                "the refund to be pending at Stripe",
                () -> assertThat(stripe.refundsOf(intent.id()))
                        .extracting(RefundView::status)
                        .containsExactly("pending"));
        assertThat(client.order(Actor.ADMIN, order.id()).status()).isEqualTo("REFUND_REQUESTED");

        // the bank returns the money
        stripe.settleRefund(stripe.refundsOf(intent.id()).get(0).id(), false);
        client.awaitOrder(order.id(), "REFUND_FAILED");
        assertThat(client.payment(Actor.ADMIN, order.id()).orElseThrow().status())
                .isEqualTo("SUCCEEDED");
        assertThat(refundRowsOf(order.id())).containsExactly("FAILED");

        // visible and recoverable: a new request, a new refund
        assertThat(client.refundOrder(Actor.ADMIN, order.id(), UUID.randomUUID().toString())
                        .status())
                .isEqualTo(202);
        TestClient.eventually(
                "a second refund at Stripe",
                () -> assertThat(stripe.refundsOf(intent.id()))
                        .extracting(RefundView::status)
                        .containsExactly("failed", "pending"));
        stripe.settleRefund(stripe.refundsOf(intent.id()).get(1).id(), true);

        client.awaitOrder(order.id(), "REFUNDED");
        client.awaitPayment(order.id(), "REFUNDED");
        assertThat(refundRowsOf(order.id())).containsExactlyInAnyOrder("FAILED", "SUCCEEDED");
        assertThat(Outbox.types(platform.paymentsDb(), order.id()))
                .containsExactly("PaymentInitiated", "PaymentSucceeded", "PaymentRefundFailed", "PaymentRefunded");
        List<String> requests = refundRequestIds(order.id());
        assertThat(requests).hasSize(2).doesNotHaveDuplicates();
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private Order paidOrder(String scenario) {
        Order order = client.placeOrder();
        client.awaitPayable(order.id());
        JsonNode confirmation = client.confirm(order.id(), scenario);
        assertThat(confirmation.get("accepted").asBoolean()).isTrue();
        return client.awaitOrder(order.id(), "PAID");
    }

    /** The payment timeout is thirty minutes; the order is made to look that old and the job finds it. */
    private void makeOverdue(UUID orderId) {
        int updated = platform.ordersDb()
                .sql("update orders set created_at = now() - interval '31 minutes' where id = :id")
                .param("id", orderId)
                .update();
        assertThat(updated).isEqualTo(1);
    }

    private static String lastHistoryReason(Order order) {
        JsonNode history = order.raw().get("history");
        return history.get(history.size() - 1).get("reason").stringValue();
    }

    private List<String> refundRowsOf(UUID orderId) {
        return platform.paymentsDb()
                .sql("select r.status from refund r join payment p on p.id = r.payment_id "
                        + "where p.order_id = :order order by r.created_at")
                .param("order", orderId)
                .query(String.class)
                .list();
    }

    private List<String> refundRequestIds(UUID orderId) {
        return platform.paymentsDb()
                .sql("select r.refund_request_id::text from refund r join payment p on p.id = r.payment_id "
                        + "where p.order_id = :order")
                .param("order", orderId)
                .query(String.class)
                .list();
    }
}
