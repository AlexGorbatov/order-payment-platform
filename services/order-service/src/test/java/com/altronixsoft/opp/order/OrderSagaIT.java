package com.altronixsoft.opp.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.altronixsoft.opp.contracts.CancelReason;
import com.altronixsoft.opp.contracts.EventEnvelope;
import com.altronixsoft.opp.contracts.OrderCancelled;
import com.altronixsoft.opp.contracts.OrderCreated;
import com.altronixsoft.opp.contracts.OrderRefundRequested;
import com.altronixsoft.opp.contracts.PaymentAttemptFailed;
import com.altronixsoft.opp.contracts.PaymentDisputed;
import com.altronixsoft.opp.contracts.PaymentEvent;
import com.altronixsoft.opp.contracts.PaymentInitiationFailed;
import com.altronixsoft.opp.contracts.PaymentRefunded;
import com.altronixsoft.opp.contracts.PaymentSucceeded;
import com.altronixsoft.opp.contracts.RefundReason;
import com.altronixsoft.opp.contracts.Topics;
import com.altronixsoft.opp.order.SagaKafka.Received;
import com.altronixsoft.opp.order.adapter.in.job.PaymentTimeoutJob;
import com.altronixsoft.opp.order.domain.Order;
import com.altronixsoft.opp.order.domain.OrderStatus;
import com.altronixsoft.opp.order.domain.StatusChange;
import com.altronixsoft.opp.order.domain.TransitionSource;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * order-service's side of the saga (architecture §6) against real PostgreSQL, Kafka and Keycloak: the test plays
 * payment-service on Kafka. Test names carry the scenario of §6 or the failure-matrix id of §15.
 */
class OrderSagaIT extends AbstractApiIT {

    private static final Duration ASYNC = Duration.ofSeconds(30);

    @Autowired
    PaymentTimeoutJob timeoutJob;

    private SagaKafka kafka;

    @BeforeEach
    void connect() {
        kafka = new SagaKafka();
    }

    @AfterEach
    void disconnect() {
        kafka.close();
    }

    private static PaymentSucceeded succeeded(UUID orderId) {
        return new PaymentSucceeded(UUID.randomUUID(), orderId, 3097, "EUR", "pi_test_1", Instant.now());
    }

    private Order order(UUID id) {
        return orders.findById(id).orElseThrow();
    }

    private void awaitStatus(UUID orderId, OrderStatus status) {
        await().atMost(ASYNC).untilAsserted(() -> assertThat(statusOf(orderId)).isEqualTo(status));
    }

    // ------------------------------------------------------------------------------------------ publishing

    @Test
    @DisplayName("§6.1: placing an order publishes OrderCreated keyed by the order id, valid against its schema")
    void placingAnOrderPublishesOrderCreated() {
        UUID correlationId = UUID.randomUUID();

        Reply reply = send(
                "POST",
                "/api/v1/orders",
                TestKeycloak.tokenOf("customer1"),
                orderBody("MUG-JAVA", 2, "STICKERS-PACK", 1),
                Map.of("Idempotency-Key", newKey(), "X-Correlation-Id", correlationId.toString()));

        assertThat(reply.status()).isEqualTo(201);
        assertThat(reply.header("X-Correlation-Id")).isEqualTo(correlationId.toString());
        UUID orderId = UUID.fromString(reply.json().path("id").stringValue());

        Received created = kafka.awaitOrderEvent(orderId, "OrderCreated");
        assertThat(created.key()).isEqualTo(orderId.toString());
        assertThat(EventSchemas.validate("OrderCreated", created.value())).isEmpty();
        assertThat(created.headers())
                .containsEntry("eventVersion", "1")
                .containsEntry("correlationId", correlationId.toString());
        EventEnvelope<?> envelope = created.envelope();
        assertThat(envelope.payload()).isEqualTo(new OrderCreated(orderId, TestKeycloak.CUSTOMER1_ID, 3097, "EUR", 2));
        assertThat(envelope.correlationId()).isEqualTo(correlationId);
        assertThat(envelope.causationId()).isNull();
        assertThat(envelope.occurredAt()).isEqualTo(order(orderId).createdAt());
    }

    @Test
    @DisplayName("§9.2: a request without a usable X-Correlation-Id gets a new one, and its events carry it")
    void aRequestWithoutCorrelationIdGetsOne() {
        Reply reply = send(
                "POST",
                "/api/v1/orders",
                TestKeycloak.tokenOf("customer1"),
                orderBody("MUG-JAVA", 1),
                Map.of("Idempotency-Key", newKey(), "X-Correlation-Id", "not-a-uuid"));

        assertThat(reply.status()).isEqualTo(201);
        UUID correlationId = UUID.fromString(reply.header("X-Correlation-Id"));
        UUID orderId = UUID.fromString(reply.json().path("id").stringValue());
        assertThat(kafka.awaitOrderEvent(orderId, "OrderCreated").envelope().correlationId())
                .isEqualTo(correlationId);
    }

    @Test
    @DisplayName("§6.4: a customer cancellation publishes OrderCancelled(CUSTOMER)")
    void aCustomerCancellationPublishesOrderCancelled() {
        UUID orderId = seedOrder(TestKeycloak.CUSTOMER1_ID);

        Reply reply = post("/api/v1/orders/" + orderId + "/cancel", TestKeycloak.tokenOf("customer1"), newKey(), null);

        assertThat(reply.status()).isEqualTo(200);
        Received cancelled = kafka.awaitOrderEvent(orderId, "OrderCancelled");
        assertThat(EventSchemas.validate("OrderCancelled", cancelled.value())).isEmpty();
        assertThat(cancelled.envelope().payload()).isEqualTo(new OrderCancelled(orderId, CancelReason.CUSTOMER));
        assertThat(cancelled.envelope().correlationId()).hasToString(reply.header("X-Correlation-Id"));
    }

    // ------------------------------------------------------------------------------------------ consuming

    @Test
    @DisplayName("§6.1: PaymentSucceeded marks the pending order PAID")
    void paymentSucceededMarksTheOrderPaid() {
        UUID orderId = seedOrder(TestKeycloak.CUSTOMER1_ID);

        EventEnvelope<PaymentEvent> event = kafka.publish(succeeded(orderId), UUID.randomUUID());

        awaitStatus(orderId, OrderStatus.PAID);
        StatusChange change = order(orderId).history().getLast();
        assertThat(change.source()).isEqualTo(TransitionSource.EVENT);
        assertThat(change.sourceEventId()).isEqualTo(event.eventId());
    }

    @Test
    @DisplayName("F03: a redelivered PaymentSucceeded changes the order once (inbox)")
    void aRedeliveredEventChangesTheOrderOnce() {
        UUID orderId = seedOrder(TestKeycloak.CUSTOMER1_ID);
        UUID correlationId = UUID.randomUUID();
        PaymentSucceeded payment = succeeded(orderId);

        EventEnvelope<PaymentEvent> event = kafka.publish(payment, correlationId);
        kafka.send(event);
        // Same key, so same partition: once this later event is applied, the redelivery has been processed too.
        kafka.publish(new PaymentDisputed(payment.paymentId(), orderId, "dp_test_1", "fraudulent"), correlationId);

        await().atMost(ASYNC).until(() -> order(orderId).disputed());
        Order order = order(orderId);
        assertThat(order.status()).isEqualTo(OrderStatus.PAID);
        assertThat(order.history())
                .filteredOn(change -> event.eventId().equals(change.sourceEventId()))
                .singleElement()
                .satisfies(change -> assertThat(change.to()).isEqualTo(OrderStatus.PAID));
        assertThat(jdbc.sql("SELECT count(*) FROM inbox_message WHERE event_id = ?")
                        .param(event.eventId())
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("§6.4: PaymentInitiationFailed cancels the order and publishes OrderCancelled caused by it")
    void initiationFailureCancelsTheOrder() {
        UUID orderId = seedOrder(TestKeycloak.CUSTOMER1_ID);
        UUID correlationId = UUID.randomUUID();

        EventEnvelope<PaymentEvent> event = kafka.publish(
                new PaymentInitiationFailed(UUID.randomUUID(), orderId, "invalid_request_error"), correlationId);

        awaitStatus(orderId, OrderStatus.CANCELLED);
        Received cancelled = kafka.awaitOrderEvent(orderId, "OrderCancelled");
        assertThat(cancelled.envelope().payload())
                .isEqualTo(new OrderCancelled(orderId, CancelReason.PAYMENT_INITIATION_FAILED));
        assertThat(cancelled.envelope().causationId()).isEqualTo(event.eventId());
        assertThat(cancelled.envelope().correlationId()).isEqualTo(correlationId);
    }

    @Test
    @DisplayName("§6.2: PaymentAttemptFailed is only recorded; the order keeps waiting for payment")
    void aFailedAttemptKeepsTheOrderPending() {
        UUID orderId = seedOrder(TestKeycloak.CUSTOMER1_ID);
        UUID paymentId = UUID.randomUUID();

        kafka.publish(
                new PaymentAttemptFailed(paymentId, orderId, "card_declined", "insufficient_funds"), UUID.randomUUID());

        await().atMost(ASYNC).until(() -> order(orderId).history().size() == 2);
        Order order = order(orderId);
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.history().getLast().reason())
                .isEqualTo("PAYMENT_ATTEMPT_FAILED card_declined/insufficient_funds");

        kafka.publish(
                new PaymentSucceeded(paymentId, orderId, 3097, "EUR", "pi_test_1", Instant.now()), UUID.randomUUID());

        awaitStatus(orderId, OrderStatus.PAID);
    }

    // ------------------------------------------------------------------------------------------ timeout

    @Test
    @DisplayName("§6.4: an order not paid within order.payment-timeout is cancelled (TIMEOUT)")
    void anUnpaidOrderTimesOut() {
        UUID orderId = seedOrder(TestKeycloak.CUSTOMER1_ID);

        clock.advance(Duration.ofMinutes(29));
        assertThat(timeoutJob.run()).isZero();
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PENDING_PAYMENT);

        clock.advance(Duration.ofMinutes(2));
        assertThat(timeoutJob.run()).isEqualTo(1);

        Order order = order(orderId);
        assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.cancelReason().name()).isEqualTo("TIMEOUT");
        assertThat(order.history().getLast().source()).isEqualTo(TransitionSource.JOB);
        Received cancelled = kafka.awaitOrderEvent(orderId, "OrderCancelled");
        assertThat(EventSchemas.validate("OrderCancelled", cancelled.value())).isEmpty();
        assertThat(cancelled.envelope().payload()).isEqualTo(new OrderCancelled(orderId, CancelReason.TIMEOUT));
        assertThat(cancelled.envelope().causationId()).isNull();
    }

    @Test
    @DisplayName("F18: PaymentSucceeded after a timeout cancellation requests a refund (LATE_PAYMENT_AFTER_CANCEL)")
    void aLatePaymentIsRefunded() {
        UUID orderId = seedOrder(TestKeycloak.CUSTOMER1_ID);
        clock.advance(Duration.ofMinutes(31));
        assertThat(timeoutJob.run()).isEqualTo(1);
        kafka.awaitOrderEvent(orderId, "OrderCancelled");
        UUID correlationId = UUID.randomUUID();

        EventEnvelope<PaymentEvent> late = kafka.publish(succeeded(orderId), correlationId);

        awaitStatus(orderId, OrderStatus.REFUND_REQUESTED);
        Order order = order(orderId);
        assertThat(order.cancelReason().name()).isEqualTo("TIMEOUT");
        Received refund = kafka.awaitOrderEvent(orderId, "OrderRefundRequested");
        assertThat(EventSchemas.validate("OrderRefundRequested", refund.value()))
                .isEmpty();
        assertThat(refund.key()).isEqualTo(orderId.toString());
        assertThat(refund.envelope().payload())
                .isEqualTo(new OrderRefundRequested(
                        orderId, order.refundRequestId(), 3097, "EUR", RefundReason.LATE_PAYMENT_AFTER_CANCEL));
        assertThat(refund.envelope().causationId()).isEqualTo(late.eventId());
        assertThat(refund.envelope().correlationId()).isEqualTo(correlationId);
        assertThat(kafka.orderEventsOf(orderId))
                .extracting(r -> r.headers().get("eventType"))
                .containsExactly("OrderCreated", "OrderCancelled", "OrderRefundRequested");
    }

    // ------------------------------------------------------------------------------------------ poison

    @Test
    @DisplayName("F15: a payment event the order cannot explain goes to the DLT without changing the order")
    void anUnexplainableEventIsDeadLettered() {
        UUID orderId = seedOrder(TestKeycloak.CUSTOMER1_ID);

        EventEnvelope<PaymentEvent> refunded = kafka.publish(
                new PaymentRefunded(UUID.randomUUID(), orderId, UUID.randomUUID(), "re_test_1", 3097),
                UUID.randomUUID());

        await().atMost(ASYNC)
                .untilAsserted(() -> assertThat(jdbc.sql(
                                        "SELECT exception_message FROM dead_letter_message WHERE original_topic = ? AND message_key = ?")
                                .param(Topics.PAYMENT_EVENTS)
                                .param(orderId.toString())
                                .query(String.class)
                                .list())
                        .singleElement()
                        .asString()
                        .contains("never requested a refund"));
        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order(orderId).history()).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM inbox_message WHERE event_id = ?")
                        .param(refunded.eventId())
                        .query(Integer.class)
                        .single())
                .as("the inbox row rolled back with the rejected change")
                .isZero();
    }
}
