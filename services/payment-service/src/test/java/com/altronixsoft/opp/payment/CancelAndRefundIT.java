package com.altronixsoft.opp.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.altronixsoft.opp.contracts.CancelReason;
import com.altronixsoft.opp.contracts.EventEnvelope;
import com.altronixsoft.opp.contracts.OrderCancelled;
import com.altronixsoft.opp.contracts.OrderCreated;
import com.altronixsoft.opp.contracts.OrderRefundRequested;
import com.altronixsoft.opp.contracts.PaymentCanceled;
import com.altronixsoft.opp.contracts.PaymentRefundFailed;
import com.altronixsoft.opp.contracts.PaymentRefunded;
import com.altronixsoft.opp.contracts.PaymentSucceeded;
import com.altronixsoft.opp.contracts.RefundReason;
import com.altronixsoft.opp.payment.adapter.in.job.PaymentCancellationJob;
import com.altronixsoft.opp.payment.adapter.in.job.RefundJob;
import com.altronixsoft.opp.payment.adapter.in.job.WebhookProcessorJob;
import com.altronixsoft.opp.payment.application.CancellationOutcome;
import com.altronixsoft.opp.payment.application.RefundOutcome;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Cancellation and refunds end to end (architecture §6.4, §6.5, §7.6): the test plays order-service on Kafka and Stripe
 * twice — WireMock answers the API calls, signed synthetic webhooks report the outcomes — and runs the workers itself.
 * Test names carry the scenario of §6 or the failure-matrix id of §15.
 */
class CancelAndRefundIT extends AbstractPaymentIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    PaymentCancellationJob cancellationJob;

    @Autowired
    RefundJob refundJob;

    @Autowired
    WebhookProcessorJob processor;

    // ------------------------------------------------------------------------------------------ helpers

    private record Paid(UUID orderId, UUID paymentId, String paymentIntentId, UUID correlationId) {}

    /** An order whose payment has a PaymentIntent ({@code REQUIRES_PAYMENT_METHOD}). */
    private Paid initiated(String paymentIntentId) {
        TestStripe.stubCreate(paymentIntentId, 3097, "EUR");
        UUID orderId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        kafka.publish(new OrderCreated(orderId, TestKeycloak.CUSTOMER1_ID, 3097, "EUR", 2), correlationId);
        UUID paymentId = awaitPayment(orderId);
        job.run();
        assertThat(statusOf(paymentId)).isEqualTo("REQUIRES_PAYMENT_METHOD");
        return new Paid(orderId, paymentId, paymentIntentId, correlationId);
    }

    /** An order whose payment Stripe reported {@code succeeded}. */
    private Paid succeeded(String paymentIntentId) {
        Paid payment = initiated(paymentIntentId);
        deliver(webhook("payment_intent.succeeded", payment).render());
        processor.run();
        assertThat(statusOf(payment.paymentId())).isEqualTo("SUCCEEDED");
        return payment;
    }

    private StripeEvents webhook(String type, Paid payment) {
        return StripeEvents.event(type)
                .created(clock.instant().truncatedTo(ChronoUnit.SECONDS))
                .paymentIntent(payment.paymentIntentId())
                .payment(payment.paymentId())
                .with("ORDER_ID", payment.orderId().toString());
    }

    private void deliver(String payload) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/webhooks/stripe"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Stripe-Signature", StripeWebhookTestSigner.sign(payload, clock.instant(), WEBHOOK_SECRET))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        try {
            assertThat(HTTP.send(request, HttpResponse.BodyHandlers.discarding())
                            .statusCode())
                    .isEqualTo(200);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private String column(String table, UUID id, String column) {
        return jdbc.sql("SELECT " + column + "::text FROM " + table + " WHERE id = :id")
                .param("id", id)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private UUID awaitRefund(UUID refundRequestId, String status) {
        return await().atMost(ASYNC)
                .until(
                        () -> jdbc.sql("SELECT id FROM refund WHERE refund_request_id = :r AND status = :s")
                                .param("r", refundRequestId)
                                .param("s", status)
                                .query(UUID.class)
                                .optional(),
                        java.util.Optional::isPresent)
                .orElseThrow();
    }

    private UUID requestRefund(Paid payment) {
        UUID refundRequestId = UUID.randomUUID();
        kafka.publish(
                new OrderRefundRequested(payment.orderId(), refundRequestId, 3097, "EUR", RefundReason.ADMIN),
                payment.correlationId());
        return refundRequestId;
    }

    // ------------------------------------------------------------------------------------------ cancellation

    @Test
    @DisplayName(
            "§6.4: the worker cancels the PaymentIntent; payment_intent.canceled makes it CANCELED + PaymentCanceled")
    void aCancelledOrderCancelsItsPaymentIntent() {
        Paid payment = initiated("pi_it_cancel_1");
        TestStripe.stubCancel(payment.paymentIntentId());
        kafka.publish(new OrderCancelled(payment.orderId(), CancelReason.CUSTOMER), payment.correlationId());
        await().atMost(ASYNC).until(() -> "true".equals(column("payment", payment.paymentId(), "cancel_requested")));

        assertThat(cancellationJob.run().count(CancellationOutcome.CANCEL_SENT)).isEqualTo(1);

        assertThat(TestStripe.cancels())
                .singleElement()
                .satisfies(request ->
                        assertThat(request.getHeader("Idempotency-Key")).isEqualTo("pi-cancel:" + payment.paymentId()));
        assertThat(column("payment", payment.paymentId(), "cancel_sent_at")).isNotNull();
        assertThat(statusOf(payment.paymentId()))
                .as("the status comes with the webhook, not with the API answer")
                .isEqualTo("REQUIRES_PAYMENT_METHOD");
        assertThat(cancellationJob.run().total()).as("nothing left to cancel").isZero();

        deliver(webhook("payment_intent.canceled", payment).render());
        processor.run();

        assertThat(statusOf(payment.paymentId())).isEqualTo("CANCELED");
        PaymentKafka.Received canceled = kafka.awaitPaymentEvent(payment.orderId(), "PaymentCanceled");
        assertThat(EventSchemas.validate("PaymentCanceled", canceled.value())).isEmpty();
        assertThat(canceled.envelope().payload())
                .isEqualTo(new PaymentCanceled(payment.paymentId(), payment.orderId(), "requested_by_customer"));
        assertThat(canceled.envelope().correlationId()).isEqualTo(payment.correlationId());
    }

    @Test
    @DisplayName("§6.4: a payment still CREATED is cancelled locally with PaymentCanceled; Stripe is never called")
    void aPaymentWithoutPaymentIntentIsCancelledLocally() {
        UUID orderId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        kafka.publish(new OrderCreated(orderId, TestKeycloak.CUSTOMER1_ID, 3097, "EUR", 2), correlationId);
        UUID paymentId = awaitPayment(orderId);

        EventEnvelope<?> cancelled = kafka.publish(new OrderCancelled(orderId, CancelReason.TIMEOUT), correlationId);

        PaymentKafka.Received canceled = kafka.awaitPaymentEvent(orderId, "PaymentCanceled");
        assertThat(statusOf(paymentId)).isEqualTo("CANCELED");
        assertThat(canceled.envelope().payload())
                .isEqualTo(new PaymentCanceled(paymentId, orderId, "canceled_before_payment_intent"));
        assertThat(canceled.envelope().causationId()).isEqualTo(cancelled.eventId());
        assertThat(cancellationJob.run().total()).isZero();
        assertThat(TestStripe.requests()).isEmpty();
    }

    @Test
    @DisplayName(
            "F19: a cancellation that races a success is not retried; the success webhook publishes PaymentSucceeded")
    void aCancellationTooLateIsNotRetriedAndTheSuccessWins() {
        Paid payment = initiated("pi_it_cancel_race");
        TestStripe.stubCancelUnexpectedState(payment.paymentIntentId());
        kafka.publish(new OrderCancelled(payment.orderId(), CancelReason.TIMEOUT), payment.correlationId());
        await().atMost(ASYNC).until(() -> "true".equals(column("payment", payment.paymentId(), "cancel_requested")));

        assertThat(cancellationJob.run().count(CancellationOutcome.TOO_LATE)).isEqualTo(1);
        clock.advance(Duration.ofHours(1));
        assertThat(cancellationJob.run().total())
                .as("unexpected_state is final: no retry")
                .isZero();
        assertThat(TestStripe.cancels()).hasSize(1);
        assertThat(column("payment", payment.paymentId(), "next_attempt_at")).isNull();

        deliver(webhook("payment_intent.succeeded", payment).render());
        processor.run();

        assertThat(statusOf(payment.paymentId())).isEqualTo("SUCCEEDED");
        PaymentKafka.Received succeeded = kafka.awaitPaymentEvent(payment.orderId(), "PaymentSucceeded");
        assertThat(succeeded.envelope().payload()).isInstanceOf(PaymentSucceeded.class);
    }

    // ------------------------------------------------------------------------------------------ refunds

    @Test
    @DisplayName("§6.5: refund requested → created at Stripe (PENDING) → charge.refunded → REFUNDED + PaymentRefunded")
    void aRefundHappyPath() {
        Paid payment = succeeded("pi_it_refund_1");
        TestStripe.stubRefund("re_it_1", payment.paymentIntentId(), "pending");
        UUID refundRequestId = requestRefund(payment);
        UUID refundId = awaitRefund(refundRequestId, "REQUESTED");

        assertThat(refundJob.run().count(RefundOutcome.CREATED_AT_STRIPE)).isEqualTo(1);

        LoggedRequest call = TestStripe.refunds().getFirst();
        assertThat(call.getHeader("Idempotency-Key")).isEqualTo("refund:" + refundId);
        Map<String, String> form = TestStripe.form(call);
        assertThat(form)
                .containsEntry("payment_intent", payment.paymentIntentId())
                .containsEntry("amount", "3097")
                .containsEntry("metadata[refundId]", refundId.toString())
                .containsEntry("metadata[orderId]", payment.orderId().toString())
                .containsEntry("metadata[paymentId]", payment.paymentId().toString());
        assertThat(column("refund", refundId, "status")).isEqualTo("PENDING");
        assertThat(column("refund", refundId, "stripe_refund_id")).isEqualTo("re_it_1");

        deliver(webhook("charge.refunded", payment).render());
        processor.run();

        assertThat(statusOf(payment.paymentId())).isEqualTo("REFUNDED");
        assertThat(column("refund", refundId, "status")).isEqualTo("SUCCEEDED");
        PaymentKafka.Received refunded = kafka.awaitPaymentEvent(payment.orderId(), "PaymentRefunded");
        assertThat(EventSchemas.validate("PaymentRefunded", refunded.value())).isEmpty();
        assertThat(refunded.envelope().payload())
                .isEqualTo(
                        new PaymentRefunded(payment.paymentId(), payment.orderId(), refundRequestId, "re_it_1", 3097));
        assertThat(refunded.envelope().correlationId()).isEqualTo(payment.correlationId());
    }

    @Test
    @DisplayName("F20: refund.failed makes the refund FAILED and publishes PaymentRefundFailed; the payment stays paid")
    void aFailedRefundIsReported() {
        Paid payment = succeeded("pi_it_refund_2");
        TestStripe.stubRefund("re_it_2", payment.paymentIntentId(), "pending");
        UUID refundRequestId = requestRefund(payment);
        UUID refundId = awaitRefund(refundRequestId, "REQUESTED");
        refundJob.run();

        deliver(webhook("refund.failed", payment)
                .with("STRIPE_REFUND_ID", "re_it_2")
                .with("REFUND_ID", refundId.toString())
                .render());
        processor.run();

        assertThat(column("refund", refundId, "status")).isEqualTo("FAILED");
        assertThat(column("refund", refundId, "failure_reason")).isEqualTo("expired_or_canceled_card");
        assertThat(statusOf(payment.paymentId())).isEqualTo("SUCCEEDED");
        PaymentKafka.Received failed = kafka.awaitPaymentEvent(payment.orderId(), "PaymentRefundFailed");
        assertThat(failed.envelope().payload())
                .isEqualTo(new PaymentRefundFailed(
                        payment.paymentId(), payment.orderId(), refundRequestId, "expired_or_canceled_card"));
    }

    @Test
    @DisplayName("F22: the same refund request delivered as several events creates one refund and one Stripe call")
    void aDuplicateRefundRequestCallsStripeOnce() {
        Paid payment = succeeded("pi_it_refund_3");
        TestStripe.stubRefund("re_it_3", payment.paymentIntentId(), "pending");
        UUID refundRequestId = UUID.randomUUID();
        OrderRefundRequested request =
                new OrderRefundRequested(payment.orderId(), refundRequestId, 3097, "EUR", RefundReason.ADMIN);
        EventEnvelope<?> first = kafka.publish(request, payment.correlationId());
        kafka.send(first);
        kafka.publish(request, payment.correlationId());
        // same partition: once this later event is consumed, the duplicates before it have been consumed too
        EventEnvelope<?> marker =
                kafka.publish(new OrderCancelled(payment.orderId(), CancelReason.CUSTOMER), payment.correlationId());
        await().atMost(ASYNC)
                .until(() -> jdbc.sql("SELECT count(*) FROM inbox_message WHERE event_id = :id")
                                .param("id", marker.eventId())
                                .query(Integer.class)
                                .single()
                        == 1);
        awaitRefund(refundRequestId, "REQUESTED");

        refundJob.run();
        clock.advance(Duration.ofHours(1));
        refundJob.run();

        assertThat(jdbc.sql("SELECT count(*) FROM refund").query(Integer.class).single())
                .isEqualTo(1);
        assertThat(TestStripe.refunds()).hasSize(1);
    }

    @Test
    @DisplayName(
            "§6.5: a refund for a payment that was never paid fails at once with PaymentRefundFailed, no Stripe call")
    void aRefundForAnUnpaidPaymentFails() {
        Paid payment = initiated("pi_it_refund_unpaid");
        UUID refundRequestId = requestRefund(payment);

        UUID refundId = awaitRefund(refundRequestId, "FAILED");

        assertThat(column("refund", refundId, "failure_reason")).isEqualTo("payment_not_succeeded");
        PaymentKafka.Received failed = kafka.awaitPaymentEvent(payment.orderId(), "PaymentRefundFailed");
        assertThat(failed.envelope().payload())
                .isEqualTo(new PaymentRefundFailed(
                        payment.paymentId(), payment.orderId(), refundRequestId, "payment_not_succeeded"));
        assertThat(refundJob.run().total()).isZero();
        assertThat(TestStripe.refunds()).isEmpty();
    }
}
