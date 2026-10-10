package com.altronixsoft.opp.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.altronixsoft.opp.contracts.CancelReason;
import com.altronixsoft.opp.contracts.EventEnvelope;
import com.altronixsoft.opp.contracts.OrderCancelled;
import com.altronixsoft.opp.contracts.OrderCreated;
import com.altronixsoft.opp.contracts.OrderRefundRequested;
import com.altronixsoft.opp.contracts.PaymentInitiated;
import com.altronixsoft.opp.contracts.PaymentInitiationFailed;
import com.altronixsoft.opp.contracts.RefundReason;
import com.altronixsoft.opp.payment.PaymentKafka.Received;
import com.altronixsoft.opp.payment.TestStripe.StubbedReply;
import com.altronixsoft.opp.payment.application.InitiationBatchResult;
import com.altronixsoft.opp.payment.application.InitiationOutcome;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The initiation of a payment end to end (architecture §6.1, ADR-0008) against real PostgreSQL and Kafka and a WireMock
 * Stripe: the test plays order-service on Kafka and runs the worker itself. Test names carry the scenario of §6 or the
 * failure-matrix id of §15.
 */
class PaymentInitiationIT extends AbstractPaymentIT {

    private static final String CUSTOMER = TestKeycloak.CUSTOMER1_ID;

    private UUID placeOrder() {
        UUID orderId = UUID.randomUUID();
        kafka.publish(new OrderCreated(orderId, CUSTOMER, 3097, "EUR", 2), UUID.randomUUID());
        awaitPayment(orderId);
        return orderId;
    }

    private void runWorkerAfter(Duration wait) {
        clock.advance(wait);
        job.run();
    }

    private static String idempotencyKey(LoggedRequest request) {
        return request.getHeader("Idempotency-Key");
    }

    private String column(UUID paymentId, String column) {
        return jdbc.sql("SELECT " + column + "::text FROM payment WHERE id = :id")
                .param("id", paymentId)
                .query(String.class)
                .list()
                .getFirst();
    }

    // ------------------------------------------------------------------------------------------ happy path

    @Test
    @DisplayName(
            "§6.1: OrderCreated creates a CREATED payment, due at once, and the worker then creates the PaymentIntent")
    void orderCreatedLeadsToAPaymentIntentAndPaymentInitiated() {
        TestStripe.stubCreate("pi_it_1", 3097, "EUR");
        UUID orderId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        EventEnvelope<?> orderCreated =
                kafka.publish(new OrderCreated(orderId, CUSTOMER, 3097, "EUR", 2), correlationId);
        UUID paymentId = awaitPayment(orderId);

        assertThat(statusOf(paymentId)).isEqualTo("CREATED");
        assertThat(TestStripe.creates())
                .as("the consumer never calls Stripe (ADR-0008)")
                .isEmpty();
        assertThat(column(paymentId, "customer_id")).isEqualTo(CUSTOMER);
        assertThat(column(paymentId, "amount_minor")).isEqualTo("3097");
        assertThat(column(paymentId, "correlation_id")).isEqualTo(correlationId.toString());
        assertThat(column(paymentId, "caused_by_event_id"))
                .isEqualTo(orderCreated.eventId().toString());

        InitiationBatchResult result = job.run();

        assertThat(result.count(InitiationOutcome.INITIATED)).isEqualTo(1);
        assertThat(statusOf(paymentId)).isEqualTo("REQUIRES_PAYMENT_METHOD");
        assertThat(column(paymentId, "stripe_payment_intent_id")).isEqualTo("pi_it_1");

        List<LoggedRequest> creates = TestStripe.creates();
        assertThat(creates).hasSize(1);
        LoggedRequest sent = creates.getFirst();
        assertThat(idempotencyKey(sent)).isEqualTo("pi-create:" + paymentId);
        Map<String, String> form = TestStripe.form(sent);
        assertThat(form)
                .containsEntry("amount", "3097")
                .containsEntry("currency", "eur")
                .containsEntry("metadata[orderId]", orderId.toString())
                .containsEntry("metadata[paymentId]", paymentId.toString());

        Received initiated = kafka.awaitPaymentEvent(orderId, "PaymentInitiated");
        assertThat(EventSchemas.validate("PaymentInitiated", initiated.value())).isEmpty();
        assertThat(initiated.key()).isEqualTo(orderId.toString());
        EventEnvelope<?> envelope = initiated.envelope();
        assertThat(envelope.payload()).isEqualTo(new PaymentInitiated(paymentId, orderId, "pi_it_1"));
        assertThat(envelope.correlationId()).isEqualTo(correlationId);
        assertThat(envelope.causationId()).isEqualTo(orderCreated.eventId());
        assertThat(envelope.producer()).isEqualTo("payment-service");
    }

    @Test
    @DisplayName(
            "F22: OrderCreated twice (a redelivery and a replay under a new event id) creates one payment and one PaymentIntent")
    void aDuplicateOrderCreatedCreatesOnePayment() {
        TestStripe.stubCreate("pi_it_2", 3097, "EUR");
        UUID orderId = UUID.randomUUID();
        EventEnvelope<?> first = kafka.publish(new OrderCreated(orderId, CUSTOMER, 3097, "EUR", 2), UUID.randomUUID());
        UUID paymentId = awaitPayment(orderId);
        kafka.send(first); // the same event again: the inbox drops it
        kafka.publish(new OrderCreated(orderId, CUSTOMER, 3097, "EUR", 2), UUID.randomUUID()); // a new event id

        // a marker behind both duplicates on the same partition proves they were consumed
        UUID marker = UUID.randomUUID();
        kafka.publish(new OrderCreated(marker, CUSTOMER, 100, "EUR", 1), UUID.randomUUID());
        UUID markerPayment = awaitPayment(marker);
        // the stub answers every create with the same PaymentIntent id: keep the marker out of the worker's way
        jdbc.sql("UPDATE payment SET next_attempt_at = NULL WHERE id = :id")
                .param("id", markerPayment)
                .update();

        assertThat(paymentCount()).isEqualTo(2);
        assertThat(paymentIdOf(orderId)).contains(paymentId);

        job.run();

        assertThat(TestStripe.creates().stream().map(PaymentInitiationIT::idempotencyKey))
                .contains("pi-create:" + paymentId)
                .doesNotHaveDuplicates();
        assertThat(TestStripe.creates().stream()
                        .filter(r -> TestStripe.form(r).get("metadata[orderId]").equals(orderId.toString())))
                .hasSize(1);
        kafka.awaitPaymentEvent(orderId, "PaymentInitiated");
        assertThat(kafka.paymentEventsOf(orderId).stream()
                        .filter(r -> "PaymentInitiated".equals(r.headers().get("eventType"))))
                .hasSize(1);
    }

    // ------------------------------------------------------------------------------------------ failures

    @Test
    @DisplayName("F04: Stripe answers 500, 500, 200: the same idempotency key every time, one PaymentIntent, one event")
    void transientFailuresAreRetriedWithTheSameKey() {
        TestStripe.stubCreateSequence(
                StubbedReply.status(500, "api_error", "api_error"),
                StubbedReply.status(500, "api_error", "api_error"),
                StubbedReply.ok(TestStripe.paymentIntent("pi_it_3", "requires_payment_method", 3097, "EUR")));
        UUID orderId = placeOrder();
        UUID paymentId = paymentIdOf(orderId).orElseThrow();

        assertThat(job.run().count(InitiationOutcome.RETRY_SCHEDULED)).isEqualTo(1);
        assertThat(statusOf(paymentId)).isEqualTo("CREATED");
        assertThat(column(paymentId, "attempts")).isEqualTo("1");
        assertThat(column(paymentId, "last_error_code")).isEqualTo("api_error");

        assertThat(job.run().total()).as("the retry is not due yet").isZero();

        runWorkerAfter(Duration.ofMinutes(1));
        assertThat(column(paymentId, "attempts")).isEqualTo("2");
        assertThat(statusOf(paymentId)).isEqualTo("CREATED");

        assertThat(job.run().total()).as("the second retry is not due yet").isZero();
        runWorkerAfter(Duration.ofMinutes(1));

        assertThat(statusOf(paymentId)).isEqualTo("REQUIRES_PAYMENT_METHOD");
        assertThat(column(paymentId, "stripe_payment_intent_id")).isEqualTo("pi_it_3");
        assertThat(TestStripe.creates()).hasSize(3);
        assertThat(TestStripe.creates().stream().map(PaymentInitiationIT::idempotencyKey))
                .containsOnly("pi-create:" + paymentId);
        kafka.awaitPaymentEvent(orderId, "PaymentInitiated");
        assertThat(kafka.paymentEventsOf(orderId)).hasSize(1);
    }

    @Test
    @DisplayName(
            "F07: Stripe rejects the request (400): INITIATION_FAILED at once and PaymentInitiationFailed, no retry")
    void aPermanentFailureFailsTheInitiation() {
        TestStripe.stubCreateSequence(StubbedReply.status(400, "invalid_request_error", "parameter_invalid_integer"));
        UUID orderId = placeOrder();
        UUID paymentId = paymentIdOf(orderId).orElseThrow();

        assertThat(job.run().count(InitiationOutcome.FAILED_PERMANENT)).isEqualTo(1);

        assertThat(statusOf(paymentId)).isEqualTo("INITIATION_FAILED");
        assertThat(column(paymentId, "last_error_code")).isEqualTo("parameter_invalid_integer");
        assertThat(column(paymentId, "next_attempt_at")).isNull();
        Received failed = kafka.awaitPaymentEvent(orderId, "PaymentInitiationFailed");
        assertThat(EventSchemas.validate("PaymentInitiationFailed", failed.value()))
                .isEmpty();
        assertThat(failed.envelope().payload())
                .isEqualTo(new PaymentInitiationFailed(paymentId, orderId, "parameter_invalid_integer"));

        runWorkerAfter(Duration.ofHours(1));
        assertThat(TestStripe.creates()).as("a failed payment is never retried").hasSize(1);
    }

    @Test
    @DisplayName("F08: a payment still CREATED after 23 h is failed without calling Stripe, whose key may be forgotten")
    void aPaymentOlderThanTheIdempotencyWindowIsFailedNotRetried() {
        TestStripe.stubCreate("pi_it_4", 3097, "EUR");
        UUID orderId = placeOrder();
        UUID paymentId = paymentIdOf(orderId).orElseThrow();

        runWorkerAfter(Duration.ofHours(23).plusMinutes(1));

        assertThat(statusOf(paymentId)).isEqualTo("INITIATION_FAILED");
        assertThat(column(paymentId, "last_error_code")).isEqualTo("idempotency_window_elapsed");
        assertThat(TestStripe.creates()).isEmpty();
        Received failed = kafka.awaitPaymentEvent(orderId, "PaymentInitiationFailed");
        assertThat(((PaymentInitiationFailed) failed.envelope().payload()).errorCode())
                .isEqualTo("idempotency_window_elapsed");
    }

    @Test
    @DisplayName("F08: just inside the window the payment is still initiated")
    void aPaymentInsideTheWindowIsStillInitiated() {
        TestStripe.stubCreate("pi_it_5", 3097, "EUR");
        UUID orderId = placeOrder();
        UUID paymentId = paymentIdOf(orderId).orElseThrow();

        runWorkerAfter(Duration.ofHours(22).plusMinutes(59));

        assertThat(statusOf(paymentId)).isEqualTo("REQUIRES_PAYMENT_METHOD");
    }

    @Test
    @DisplayName(
            "F05: a crash after Stripe answered and before the commit: the retry sends the same key and ends with one PaymentIntent")
    void aCrashBeforeTheCommitIsRepairedByTheRetry() {
        TestStripe.stubCreate("pi_it_6", 3097, "EUR");
        UUID orderId = placeOrder();
        UUID paymentId = paymentIdOf(orderId).orElseThrow();
        doThrow(new IllegalStateException("simulated crash before commit"))
                .doCallRealMethod()
                .when(events)
                .publish(any(), any(), any());

        InitiationBatchResult crashed = job.run();

        assertThat(crashed.count(InitiationOutcome.ERROR)).isEqualTo(1);
        assertThat(TestStripe.creates()).hasSize(1);
        assertThat(statusOf(paymentId)).as("the transaction rolled back").isEqualTo("CREATED");
        assertThat(column(paymentId, "stripe_payment_intent_id")).isNull();
        assertThat(job.run().total())
                .as("still leased: nobody else works on it")
                .isZero();

        runWorkerAfter(Duration.ofMinutes(6)); // the lease has expired

        assertThat(statusOf(paymentId)).isEqualTo("REQUIRES_PAYMENT_METHOD");
        assertThat(column(paymentId, "stripe_payment_intent_id")).isEqualTo("pi_it_6");
        assertThat(TestStripe.creates()).hasSize(2);
        assertThat(TestStripe.creates().stream().map(PaymentInitiationIT::idempotencyKey))
                .containsOnly("pi-create:" + paymentId);
        kafka.awaitPaymentEvent(orderId, "PaymentInitiated");
        assertThat(kafka.paymentEventsOf(orderId)).hasSize(1);
    }

    @Test
    @DisplayName("ADR-0008: the Stripe call is made outside any database transaction")
    void stripeIsCalledOutsideATransaction() {
        UUID orderId = placeOrder();
        UUID paymentId = paymentIdOf(orderId).orElseThrow();
        // while Stripe "thinks", another connection must be able to read AND update the claimed row: no lock is held
        TestStripe.SERVER.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                        com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo("/v1/payment_intents"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                        .withStatus(200)
                        .withFixedDelay(1500)
                        .withHeader("Content-Type", "application/json")
                        .withBody(TestStripe.paymentIntent("pi_it_7", "requires_payment_method", 3097, "EUR"))));
        Thread worker = Thread.ofPlatform().start(job::run);

        await().atMost(Duration.ofSeconds(10))
                .until(() -> !TestStripe.requests().isEmpty());
        long started = System.nanoTime();
        jdbc.sql("UPDATE payment SET last_error_message = 'touched while Stripe was being called' WHERE id = :id")
                .param("id", paymentId)
                .update();
        Duration waited = Duration.ofNanos(System.nanoTime() - started);
        assertThat(waited).as("the row was not locked by the worker").isLessThan(Duration.ofMillis(1000));
        try {
            worker.join(Duration.ofSeconds(20));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(statusOf(paymentId)).isEqualTo("REQUIRES_PAYMENT_METHOD");
    }

    // ------------------------------------------------------------------------------------------ cancel and refund

    @Test
    @DisplayName(
            "§6.2: OrderCancelled before the PaymentIntent exists cancels the payment locally; Stripe is never called")
    void cancellingBeforeInitiationNeverCallsStripe() {
        TestStripe.stubCreate("pi_it_8", 3097, "EUR");
        UUID orderId = placeOrder();
        UUID paymentId = paymentIdOf(orderId).orElseThrow();

        kafka.publish(new OrderCancelled(orderId, CancelReason.CUSTOMER), UUID.randomUUID());
        await().atMost(ASYNC)
                .untilAsserted(() -> assertThat(statusOf(paymentId)).isEqualTo("CANCELED"));

        assertThat(job.run().total()).isZero();
        assertThat(TestStripe.creates()).isEmpty();
    }

    @Test
    @DisplayName(
            "§6.2: OrderCancelled after the PaymentIntent exists only marks cancel_requested; the cancellation worker cancels")
    void cancellingAfterInitiationOnlyRequestsTheCancellation() {
        TestStripe.stubCreate("pi_it_9", 3097, "EUR");
        UUID orderId = placeOrder();
        UUID paymentId = paymentIdOf(orderId).orElseThrow();
        job.run();

        kafka.publish(new OrderCancelled(orderId, CancelReason.TIMEOUT), UUID.randomUUID());

        await().atMost(ASYNC)
                .untilAsserted(
                        () -> assertThat(column(paymentId, "cancel_requested")).isEqualTo("true"));
        assertThat(statusOf(paymentId)).isEqualTo("REQUIRES_PAYMENT_METHOD");
        assertThat(TestStripe.requests().stream().filter(r -> r.getUrl().contains("cancel")))
                .as("no Stripe call from the consumer")
                .isEmpty();
    }

    @Test
    @DisplayName("§6.4/F22: OrderRefundRequested creates one REQUESTED refund, also when it is delivered twice")
    void aRefundRequestCreatesOneRequestedRefund() {
        UUID orderId = placeOrder();
        UUID paymentId = paymentIdOf(orderId).orElseThrow();
        jdbc.sql("UPDATE payment SET status = 'SUCCEEDED', stripe_payment_intent_id = 'pi_it_paid' WHERE id = :id")
                .param("id", paymentId)
                .update();
        UUID refundRequestId = UUID.randomUUID();
        OrderRefundRequested request =
                new OrderRefundRequested(orderId, refundRequestId, 3097, "EUR", RefundReason.ADMIN);

        EventEnvelope<?> first = kafka.publish(request, UUID.randomUUID());
        await().atMost(ASYNC).until(() -> refundCount() == 1);
        kafka.send(first);
        kafka.publish(request, UUID.randomUUID());
        UUID marker = UUID.randomUUID();
        kafka.publish(new OrderCreated(marker, CUSTOMER, 100, "EUR", 1), UUID.randomUUID());
        awaitPayment(marker);

        assertThat(refundCount()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status || ':' || amount_minor || ':' || refund_request_id FROM refund")
                        .query(String.class)
                        .single())
                .isEqualTo("REQUESTED:3097:" + refundRequestId);
        assertThat(TestStripe.requests()).as("the consumer never calls Stripe").isEmpty();
    }

    private int refundCount() {
        return jdbc.sql("SELECT count(*) FROM refund").query(Integer.class).single();
    }
}
