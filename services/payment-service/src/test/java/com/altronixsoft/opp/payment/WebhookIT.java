package com.altronixsoft.opp.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.altronixsoft.opp.contracts.EventEnvelope;
import com.altronixsoft.opp.contracts.OrderCreated;
import com.altronixsoft.opp.contracts.PaymentSucceeded;
import com.altronixsoft.opp.payment.adapter.in.job.WebhookProcessorJob;
import com.altronixsoft.opp.payment.application.WebhookBatchResult;
import com.altronixsoft.opp.payment.application.WebhookOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Stripe webhooks end to end (architecture §6.6, §8.3, ADR-0009): signed requests over HTTP, the stored events, the
 * processor (run by the test), the payment, the outbox and Kafka. Requests are signed with
 * {@link StripeWebhookTestSigner}; payloads are the synthetic fixtures of {@code stripe/events}. Test names carry the
 * failure-matrix id of §15.
 */
class WebhookIT extends AbstractPaymentIT {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Autowired
    WebhookProcessorJob processor;

    @Autowired
    MeterRegistry meters;

    // ------------------------------------------------------------------------------------------ helpers

    private Reply deliver(String payload, String signature) {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/webhooks/stripe"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(payload));
        if (signature != null) {
            request.header("Stripe-Signature", signature);
        }
        try {
            HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.headers(), response.body());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Delivers {@code payload} signed now with the current secret, and expects it to be accepted. */
    private void deliver(String payload) {
        assertThat(deliver(payload, StripeWebhookTestSigner.sign(payload, clock.instant(), WEBHOOK_SECRET))
                        .status())
                .isEqualTo(200);
    }

    /** A payment of a fresh order whose PaymentIntent {@code paymentIntentId} has been created by the worker. */
    private Initiated initiated(String paymentIntentId) {
        TestStripe.stubCreate(paymentIntentId, 3097, "EUR");
        UUID orderId = UUID.randomUUID();
        kafka.publish(new OrderCreated(orderId, TestKeycloak.CUSTOMER1_ID, 3097, "EUR", 2), UUID.randomUUID());
        UUID paymentId = awaitPayment(orderId);
        job.run();
        assertThat(statusOf(paymentId)).isEqualTo("REQUIRES_PAYMENT_METHOD");
        return new Initiated(orderId, paymentId, paymentIntentId);
    }

    private record Initiated(UUID orderId, UUID paymentId, String paymentIntentId) {}

    private StripeEvents event(String type, Initiated payment, Instant created) {
        return StripeEvents.event(type)
                .created(created)
                .paymentIntent(payment.paymentIntentId())
                .payment(payment.paymentId())
                .with("ORDER_ID", payment.orderId().toString());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }

    private String webhookStatus(String eventId) {
        return jdbc.sql("SELECT status FROM stripe_webhook_event WHERE event_id = :id")
                .param("id", eventId)
                .query(String.class)
                .optional()
                .orElse("absent");
    }

    private int storedWebhooks() {
        return jdbc.sql("SELECT count(*) FROM stripe_webhook_event")
                .query(Integer.class)
                .single();
    }

    private List<String> outboxTypes(UUID orderId) {
        return jdbc.sql("SELECT event_type FROM outbox_event WHERE partition_key = :key ORDER BY created_at, id")
                .param("key", orderId.toString())
                .query(String.class)
                .list();
    }

    private double counter(String name, String... tags) {
        var counter = meters.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    // ------------------------------------------------------------------------------------------ ingress

    @Test
    @DisplayName("§6.6: a correctly signed event is stored as RECEIVED and acknowledged with 200, nothing applied yet")
    void aSignedEventIsStoredAndAcknowledged() {
        Initiated payment = initiated("pi_it_wh_1");
        StripeEvents succeeded = event("payment_intent.succeeded", payment, now());

        deliver(succeeded.render());

        assertThat(webhookStatus(succeeded.eventId())).isEqualTo("RECEIVED");
        assertThat(statusOf(payment.paymentId()))
                .as("no business logic on receipt")
                .isEqualTo("REQUIRES_PAYMENT_METHOD");
        assertThat(counter("webhook.received", "type", "payment_intent.succeeded"))
                .isPositive();
    }

    @Test
    @DisplayName("F12: a wrong signature is refused with 400 and nothing is stored")
    void aWrongSignatureIsRefused() {
        String payload = StripeEvents.event("payment_intent.succeeded")
                .created(now())
                .paymentIntent("pi_it_forged")
                .render();
        double before = counter("webhook.signature.failures", "reason", "invalid_signature");

        Reply reply = deliver(payload, StripeWebhookTestSigner.sign(payload, clock.instant(), "whsec_attacker"));

        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.problemType()).isEqualTo("urn:problem-type:invalid-webhook");
        assertThat(deliver(payload, null).status()).isEqualTo(400);
        assertThat(storedWebhooks()).isZero();
        assertThat(counter("webhook.signature.failures", "reason", "invalid_signature") - before)
                .isEqualTo(1);
    }

    @Test
    @DisplayName("F12: a correctly signed event whose timestamp is older than 5 minutes (a replay) is refused")
    void anOldSignatureIsRefused() {
        String payload = StripeEvents.event("payment_intent.succeeded")
                .created(now())
                .paymentIntent("pi_it_replayed")
                .render();

        Reply reply = deliver(
                payload, StripeWebhookTestSigner.sign(payload, clock.instant().minusSeconds(301), WEBHOOK_SECRET));

        assertThat(reply.status()).isEqualTo(400);
        assertThat(storedWebhooks()).isZero();
    }

    @Test
    @DisplayName("§8.3: while a secret is rolled, a signature with the previous secret is still accepted")
    void thePreviousSecretWorksDuringARotation() {
        String payload = StripeEvents.event("customer.created").created(now()).render();

        Reply reply = deliver(payload, StripeWebhookTestSigner.sign(payload, clock.instant(), PREVIOUS_WEBHOOK_SECRET));

        assertThat(reply.status()).isEqualTo(200);
        assertThat(storedWebhooks()).isEqualTo(1);
    }

    @Test
    @DisplayName("F13: a live-mode event is refused with 400, logged as an error and counted")
    void aLiveModeEventIsRefused() {
        String payload = StripeEvents.event("payment_intent.succeeded")
                .created(now())
                .paymentIntent("pi_it_live")
                .livemode(true)
                .render();
        double before = counter("webhook.livemode.rejected");

        Reply reply = deliver(payload, StripeWebhookTestSigner.sign(payload, clock.instant(), WEBHOOK_SECRET));

        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.problemType()).isEqualTo("urn:problem-type:livemode-webhook");
        assertThat(storedWebhooks()).isZero();
        assertThat(counter("webhook.livemode.rejected") - before).isEqualTo(1);
    }

    @Test
    void aBodyLargerThan256KbIsRefused() {
        String payload = "{\"padding\":\"" + "x".repeat(256 * 1024) + "\"}";

        Reply reply = deliver(payload, StripeWebhookTestSigner.sign(payload, clock.instant(), WEBHOOK_SECRET));

        assertThat(reply.status()).isEqualTo(413);
        assertThat(storedWebhooks()).isZero();
    }

    // ------------------------------------------------------------------------------------------ processing

    @Test
    @DisplayName("F09: the same event delivered three times is processed once and publishes one PaymentSucceeded")
    void aRedeliveredEventIsProcessedOnce() {
        Initiated payment = initiated("pi_it_wh_dup");
        String payload = event("payment_intent.succeeded", payment, now()).render();

        deliver(payload);
        deliver(payload);
        deliver(payload);
        processor.run();
        processor.run();

        assertThat(storedWebhooks()).isEqualTo(1);
        assertThat(statusOf(payment.paymentId())).isEqualTo("SUCCEEDED");
        assertThat(outboxTypes(payment.orderId()))
                .filteredOn("PaymentSucceeded"::equals)
                .hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM payment_status_history WHERE payment_id = :id AND to_status = "
                                + "'SUCCEEDED'")
                        .param("id", payment.paymentId())
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("F10: succeeded delivered before processing ends SUCCEEDED; the late processing is stale")
    void outOfOrderEventsEndSucceeded() {
        Initiated payment = initiated("pi_it_wh_order");
        Instant t = now();
        StripeEvents processing = event("payment_intent.processing", payment, t);
        StripeEvents succeeded = event("payment_intent.succeeded", payment, t.plusSeconds(1));
        double staleBefore = counter("webhook.stale.ignored");

        deliver(succeeded.render());
        processor.run();
        deliver(processing.render());
        processor.run();

        assertThat(statusOf(payment.paymentId())).isEqualTo("SUCCEEDED");
        assertThat(webhookStatus(processing.eventId())).isEqualTo("PROCESSED");
        assertThat(counter("webhook.stale.ignored") - staleBefore).isEqualTo(1);
        assertThat(outboxTypes(payment.orderId())).containsExactly("PaymentInitiated", "PaymentSucceeded");
    }

    @Test
    @DisplayName("§6.2: payment_failed and then succeeded on the same PaymentIntent ends SUCCEEDED")
    void aFailedAttemptThenSuccess() {
        Initiated payment = initiated("pi_it_wh_retry");
        Instant t = now();

        deliver(event("payment_intent.payment_failed", payment, t).render());
        processor.run();
        assertThat(statusOf(payment.paymentId())).isEqualTo("REQUIRES_PAYMENT_METHOD");
        assertThat(jdbc.sql("SELECT last_error_code || '/' || last_decline_code FROM payment WHERE id = :id")
                        .param("id", payment.paymentId())
                        .query(String.class)
                        .single())
                .isEqualTo("card_declined/insufficient_funds");

        deliver(event("payment_intent.succeeded", payment, t.plusSeconds(30)).render());
        processor.run();

        assertThat(statusOf(payment.paymentId())).isEqualTo("SUCCEEDED");
        assertThat(outboxTypes(payment.orderId()))
                .containsExactly("PaymentInitiated", "PaymentAttemptFailed", "PaymentSucceeded");
    }

    @Test
    @DisplayName("§8.3: an event of a type the platform does not handle is IGNORED")
    void anUnknownTypeIsIgnored() {
        StripeEvents customer = StripeEvents.event("customer.created").created(now());

        deliver(customer.render());
        WebhookBatchResult result = processor.run();

        assertThat(result.count(WebhookOutcome.IGNORED)).isEqualTo(1);
        assertThat(webhookStatus(customer.eventId())).isEqualTo("IGNORED");
    }

    @Test
    @DisplayName("F14: a handler that fails twice is retried with backoff and then succeeds")
    void aHandlerThatFailsTwiceSucceedsLater() {
        Initiated payment = initiated("pi_it_wh_flaky");
        StripeEvents succeeded = event("payment_intent.succeeded", payment, now());
        doThrow(new IllegalStateException("outbox unavailable"))
                .doThrow(new IllegalStateException("outbox unavailable"))
                .doCallRealMethod()
                .when(events)
                .publish(any(), any(), any());
        deliver(succeeded.render());

        assertThat(processor.run().count(WebhookOutcome.RETRY_SCHEDULED)).isEqualTo(1);
        assertThat(webhookStatus(succeeded.eventId())).isEqualTo("FAILED");
        clock.advance(Duration.ofSeconds(10));
        assertThat(processor.run().count(WebhookOutcome.RETRY_SCHEDULED)).isEqualTo(1);
        clock.advance(Duration.ofSeconds(20));
        assertThat(processor.run().count(WebhookOutcome.PROCESSED)).isEqualTo(1);

        assertThat(webhookStatus(succeeded.eventId())).isEqualTo("PROCESSED");
        assertThat(statusOf(payment.paymentId())).isEqualTo("SUCCEEDED");
        assertThat(outboxTypes(payment.orderId()))
                .as("the failed attempts rolled back completely")
                .containsExactly("PaymentInitiated", "PaymentSucceeded");
        assertThat(jdbc.sql("SELECT attempts FROM stripe_webhook_event WHERE event_id = :id")
                        .param("id", succeeded.eventId())
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    @DisplayName(
            "F14: a handler that always fails leaves the event DEAD after max attempts, counted, payment untouched")
    void aHandlerThatAlwaysFailsEndsDead() {
        Initiated payment = initiated("pi_it_wh_dead");
        StripeEvents succeeded = event("payment_intent.succeeded", payment, now());
        doThrow(new IllegalStateException("outbox unavailable")).when(events).publish(any(), any(), any());
        double deadBefore = counter("webhook.dead");
        deliver(succeeded.render());

        for (int run = 0; run < 3; run++) {
            processor.run();
            clock.advance(Duration.ofMinutes(5));
        }

        assertThat(webhookStatus(succeeded.eventId())).isEqualTo("DEAD");
        assertThat(counter("webhook.dead") - deadBefore).isEqualTo(1);
        assertThat(statusOf(payment.paymentId())).isEqualTo("REQUIRES_PAYMENT_METHOD");
        assertThat(processor.run().total())
                .as("a DEAD event is not picked up again")
                .isZero();
    }

    @Test
    @DisplayName("§13: the processing lag is measured")
    void theProcessingLagIsMeasured() {
        long before = meters.find("webhook.processing.lag").timer() == null
                ? 0
                : meters.find("webhook.processing.lag").timer().count();
        deliver(StripeEvents.event("customer.created").created(now()).render());

        processor.run();

        assertThat(meters.find("webhook.processing.lag").timer().count() - before)
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------------------------------ end to end

    @Test
    @DisplayName("§6.1: OrderCreated → PaymentIntent → signed payment_intent.succeeded → PaymentSucceeded on Kafka")
    void endToEndAPaidOrderIsAnnounced() {
        UUID correlationId = UUID.randomUUID();
        TestStripe.stubCreate("pi_it_wh_e2e", 3097, "EUR");
        UUID orderId = UUID.randomUUID();
        EventEnvelope<?> orderCreated =
                kafka.publish(new OrderCreated(orderId, TestKeycloak.CUSTOMER1_ID, 3097, "EUR", 2), correlationId);
        UUID paymentId = awaitPayment(orderId);
        job.run();
        kafka.awaitPaymentEvent(orderId, "PaymentInitiated");

        Instant created = now();
        deliver(StripeEvents.event("payment_intent.succeeded")
                .created(created)
                .paymentIntent("pi_it_wh_e2e")
                .payment(paymentId)
                .with("ORDER_ID", orderId.toString())
                .render());
        processor.run();

        PaymentKafka.Received received = kafka.awaitPaymentEvent(orderId, "PaymentSucceeded");
        assertThat(EventSchemas.validate("PaymentSucceeded", received.value())).isEmpty();
        EventEnvelope<?> envelope = received.envelope();
        assertThat(envelope.payload())
                .isEqualTo(new PaymentSucceeded(paymentId, orderId, 3097, "EUR", "pi_it_wh_e2e", created));
        assertThat(envelope.correlationId()).isEqualTo(correlationId);
        assertThat(envelope.causationId()).isEqualTo(orderCreated.eventId());
        assertThat(received.key()).isEqualTo(orderId.toString());
    }
}
