package com.altronixsoft.opp.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.altronixsoft.opp.e2e.support.Actor;
import com.altronixsoft.opp.e2e.support.Outbox;
import com.altronixsoft.opp.e2e.support.StripeSimulator.Operation;
import com.altronixsoft.opp.e2e.support.StripeSimulator.PaymentIntentView;
import com.altronixsoft.opp.e2e.support.TestClient;
import com.altronixsoft.opp.e2e.support.TestClient.Order;
import com.altronixsoft.opp.e2e.support.TestClient.Payment;
import com.altronixsoft.opp.e2e.support.TestClient.Reply;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Architecture §6.1 - §6.3: a customer pays, is declined and tries again, or has to authenticate. */
class PaymentFlowsIT extends E2eTest {

    @Test
    @DisplayName("Flow 6.1: happy path - order, PaymentIntent, payment, webhook, order PAID")
    void flow_6_1_happyPath() {
        UUID correlationId = UUID.randomUUID();
        Reply created = client.createOrder(
                Actor.CUSTOMER,
                UUID.randomUUID().toString(),
                TestClient.BASKET,
                Map.of("X-Correlation-Id", correlationId.toString()));
        assertThat(created.status()).isEqualTo(201);
        Order order = Order.of(created.json());
        assertThat(order.status()).isEqualTo("PENDING_PAYMENT");
        assertThat(order.totalMinor()).isEqualTo(TestClient.BASKET_TOTAL_MINOR);
        assertThat(created.header("X-Correlation-Id")).isEqualTo(correlationId.toString());

        // payment-service reacts to OrderCreated and gets a PaymentIntent from Stripe
        Payment payable = client.awaitPayable(order.id());
        PaymentIntentView intent = stripe.paymentIntentOf(order.id());
        assertThat(payable.stripePaymentIntentId()).isEqualTo(intent.id());
        assertThat(intent.amountMinor()).isEqualTo(TestClient.BASKET_TOTAL_MINOR);
        assertThat(stripe.calls(Operation.CREATE_PAYMENT_INTENT, order.id()))
                .singleElement()
                .satisfies(call -> assertThat(call.idempotencyKey()).isEqualTo("pi-create:" + payable.paymentId()));

        // only the paying customer receives the client secret, and it is never cached
        Reply asCustomer = client.paymentReply(Actor.CUSTOMER, order.id());
        assertThat(asCustomer.status()).isEqualTo(200);
        assertThat(asCustomer.header("Cache-Control")).contains("no-store");
        assertThat(asCustomer.json().get("clientSecret").stringValue()).isEqualTo(intent.clientSecret());
        assertThat(client.payment(Actor.ADMIN, order.id()).orElseThrow().clientSecret())
                .isNull();
        assertThat(client.paymentReply(Actor.OTHER_CUSTOMER, order.id()).status())
                .isEqualTo(404);

        // the customer pays; Stripe's webhook brings the result
        JsonNode confirmation = client.confirm(order.id(), "success");
        assertThat(confirmation.get("accepted").asBoolean()).isTrue();
        client.awaitOrder(order.id(), "PAID");
        client.awaitPayment(order.id(), "SUCCEEDED");

        Order paid = client.order(Actor.CUSTOMER, order.id());
        assertThat(paid.raw()
                        .get("history")
                        .get(paid.raw().get("history").size() - 1)
                        .get("to")
                        .stringValue())
                .isEqualTo("PAID");
        assertThat(stripe.paymentIntentOf(order.id()).status()).isEqualTo("succeeded");

        // every event exactly once, and the flow keeps one correlation id from the request to the last event
        assertThat(Outbox.types(platform.ordersDb(), order.id())).containsExactly("OrderCreated");
        assertThat(Outbox.types(platform.paymentsDb(), order.id()))
                .containsExactly("PaymentInitiated", "PaymentSucceeded");
        assertThat(Outbox.correlationId(platform.ordersDb(), order.id(), "OrderCreated"))
                .isEqualTo(correlationId.toString());
        assertThat(Outbox.correlationId(platform.paymentsDb(), order.id(), "PaymentInitiated"))
                .isEqualTo(correlationId.toString());
    }

    @Test
    @DisplayName("Flow 6.2: a declined card leaves the order open; a second attempt on the same PaymentIntent pays")
    void flow_6_2_declineThenSuccessfulRetry() {
        Order order = client.placeOrder();
        Payment payable = client.awaitPayable(order.id());

        JsonNode declined = client.confirm(order.id(), "decline");
        assertThat(declined.get("accepted").asBoolean()).isFalse();
        assertThat(declined.get("errorCode").stringValue()).isEqualTo("card_declined");

        // the failed attempt is recorded and announced, but the order and the PaymentIntent stay open
        TestClient.eventually(
                "the failed attempt to be announced",
                () -> assertThat(Outbox.count(platform.paymentsDb(), order.id(), "PaymentAttemptFailed"))
                        .isEqualTo(1));
        TestClient.eventually(
                "the failed attempt to be recorded",
                () -> assertThat(client.payment(Actor.ADMIN, order.id())
                                .orElseThrow()
                                .lastErrorCode())
                        .isEqualTo("card_declined"));
        assertThat(client.order(Actor.CUSTOMER, order.id()).status()).isEqualTo("PENDING_PAYMENT");
        assertThat(client.payment(Actor.ADMIN, order.id()).orElseThrow().status())
                .isEqualTo("REQUIRES_PAYMENT_METHOD");

        // a second, different card on the same PaymentIntent
        client.confirm(order.id(), "success");
        client.awaitOrder(order.id(), "PAID");

        assertThat(stripe.paymentIntentsOf(order.id())).singleElement().satisfies(pi -> {
            assertThat(pi.id()).isEqualTo(payable.stripePaymentIntentId());
            assertThat(pi.status()).isEqualTo("succeeded");
        });
        assertThat(Outbox.types(platform.paymentsDb(), order.id()))
                .containsExactly("PaymentInitiated", "PaymentAttemptFailed", "PaymentSucceeded");
    }

    @Test
    @DisplayName("Flow 6.3: 3DS - the order waits while the customer authenticates, then it is paid")
    void flow_6_3_authenticationRequiredThenSuccess() {
        Order order = client.placeOrder();
        client.awaitPayable(order.id());
        String intentId = stripe.paymentIntentOf(order.id()).id();

        client.confirm(order.id(), "requires_3ds");
        client.awaitPayment(order.id(), "REQUIRES_ACTION");
        assertThat(client.order(Actor.CUSTOMER, order.id()).status()).isEqualTo("PENDING_PAYMENT");
        TestClient.eventually(
                "PaymentActionRequired",
                () -> assertThat(Outbox.count(platform.paymentsDb(), order.id(), "PaymentActionRequired"))
                        .isEqualTo(1));

        // the customer completes the challenge in the browser
        stripe.completeAuthentication(intentId, true);
        client.awaitOrder(order.id(), "PAID");
        client.awaitPayment(order.id(), "SUCCEEDED");
        assertThat(Outbox.types(platform.paymentsDb(), order.id()))
                .containsExactly("PaymentInitiated", "PaymentActionRequired", "PaymentSucceeded");
    }

    @Test
    @DisplayName("Flow 6.3: 3DS - failed authentication sends the customer back to choose a payment method")
    void flow_6_3_authenticationFailedThenAnotherCardPays() {
        Order order = client.placeOrder();
        client.awaitPayable(order.id());
        String intentId = stripe.paymentIntentOf(order.id()).id();

        client.confirm(order.id(), "requires_3ds");
        client.awaitPayment(order.id(), "REQUIRES_ACTION");

        stripe.completeAuthentication(intentId, false);
        client.awaitPayment(order.id(), "REQUIRES_PAYMENT_METHOD");
        assertThat(client.order(Actor.CUSTOMER, order.id()).status()).isEqualTo("PENDING_PAYMENT");

        client.confirm(order.id(), "success");
        client.awaitOrder(order.id(), "PAID");
        assertThat(List.copyOf(stripe.paymentIntentsOf(order.id()))).hasSize(1);
    }
}
