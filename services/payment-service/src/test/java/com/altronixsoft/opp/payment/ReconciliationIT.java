package com.altronixsoft.opp.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.altronixsoft.opp.contracts.OrderCreated;
import com.altronixsoft.opp.contracts.PaymentSucceeded;
import com.altronixsoft.opp.payment.adapter.in.job.ReconciliationJob;
import com.altronixsoft.opp.payment.adapter.in.job.WebhookProcessorJob;
import com.altronixsoft.opp.payment.application.ReconciliationSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Reconciliation with Stripe end to end (architecture §8.4, ADR-0010): WireMock answers {@code retrieve}, the clock is
 * moved past the stale period, and the run is triggered through the admin API or the job. Test names carry the
 * failure-matrix id of §15.
 */
class ReconciliationIT extends AbstractPaymentIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired
    ReconciliationJob reconciliation;

    @Autowired
    WebhookProcessorJob processor;

    @Autowired
    MeterRegistry meters;

    private record Initiated(UUID orderId, UUID paymentId, String paymentIntentId) {}

    private Initiated initiated(String paymentIntentId) {
        TestStripe.stubCreate(paymentIntentId, 3097, "EUR");
        UUID orderId = UUID.randomUUID();
        kafka.publish(new OrderCreated(orderId, TestKeycloak.CUSTOMER1_ID, 3097, "EUR", 2), UUID.randomUUID());
        UUID paymentId = awaitPayment(orderId);
        job.run();
        assertThat(statusOf(paymentId)).isEqualTo("REQUIRES_PAYMENT_METHOD");
        return new Initiated(orderId, paymentId, paymentIntentId);
    }

    private int outboxCount(UUID orderId, String eventType) {
        return jdbc.sql("SELECT count(*) FROM outbox_event WHERE partition_key = :key AND event_type = :type")
                .param("key", orderId.toString())
                .param("type", eventType)
                .query(Integer.class)
                .single();
    }

    /** What reconciliation must not change when Stripe agrees: the payment's state and its history. */
    private String state(UUID paymentId) {
        return jdbc.sql(
                        "SELECT status || '/' || version || '/' || updated_at || '/' || coalesce(last_stripe_event_at::text, '')"
                                + " || '/' || (SELECT count(*) FROM payment_status_history h WHERE h.payment_id = p.id)"
                                + " FROM payment p WHERE id = :id")
                .param("id", paymentId)
                .query(String.class)
                .single();
    }

    private double drift(String from, String to) {
        var counter =
                meters.find("reconciliation.drift").tags("from", from, "to", to).counter();
        return counter == null ? 0 : counter.count();
    }

    private void deliverSucceeded(Initiated payment) {
        String payload = StripeEvents.event("payment_intent.succeeded")
                .created(clock.instant().truncatedTo(ChronoUnit.SECONDS))
                .paymentIntent(payment.paymentIntentId())
                .payment(payment.paymentId())
                .with("ORDER_ID", payment.orderId().toString())
                .render();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/webhooks/stripe"))
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

    @Test
    @DisplayName(
            "F11: a lost succeeded webhook is found by reconciliation: SUCCEEDED + PaymentSucceeded + drift metric")
    void aLostWebhookIsCaughtUp() {
        Initiated payment = initiated("pi_it_rec_1");
        TestStripe.stubRetrieve(payment.paymentIntentId(), "succeeded", 3097, "EUR");
        double driftBefore = drift("REQUIRES_PAYMENT_METHOD", "SUCCEEDED");
        clock.advance(Duration.ofMinutes(11));

        Reply run = post("/admin/reconciliation/run", TestKeycloak.opsClientToken());

        assertThat(run.status()).isEqualTo(200);
        assertThat(run.json().path("trigger").stringValue()).isEqualTo("MANUAL");
        assertThat(run.json().path("checked").intValue()).isEqualTo(1);
        assertThat(run.json().path("drifted").intValue()).isEqualTo(1);
        assertThat(run.json().path("drifts").get(0).path("to").stringValue()).isEqualTo("SUCCEEDED");
        assertThat(statusOf(payment.paymentId())).isEqualTo("SUCCEEDED");
        assertThat(jdbc.sql("SELECT source FROM payment_status_history WHERE payment_id = :id ORDER BY id DESC LIMIT 1")
                        .param("id", payment.paymentId())
                        .query(String.class)
                        .single())
                .isEqualTo("RECONCILIATION");
        PaymentKafka.Received succeeded = kafka.awaitPaymentEvent(payment.orderId(), "PaymentSucceeded");
        assertThat(EventSchemas.validate("PaymentSucceeded", succeeded.value())).isEmpty();
        assertThat(succeeded.envelope().payload()).isInstanceOf(PaymentSucceeded.class);
        assertThat(drift("REQUIRES_PAYMENT_METHOD", "SUCCEEDED") - driftBefore).isEqualTo(1);

        Reply last = get("/admin/reconciliation/last", TestKeycloak.opsClientToken());
        assertThat(last.status()).isEqualTo(200);
        assertThat(last.json().path("drifted").intValue()).isEqualTo(1);
    }

    @Test
    void whenStripeAgreesNothingIsWrittenOrPublished() {
        Initiated payment = initiated("pi_it_rec_2");
        TestStripe.stubRetrieve(payment.paymentIntentId(), "requires_payment_method", 3097, "EUR");
        String before = state(payment.paymentId());
        clock.advance(Duration.ofMinutes(11));

        ReconciliationSummary summary = reconciliation.run();

        assertThat(summary.checked()).isEqualTo(1);
        assertThat(summary.unchanged()).isEqualTo(1);
        assertThat(summary.drifted()).isZero();
        assertThat(state(payment.paymentId()))
                .as("payment and history untouched")
                .isEqualTo(before);
        assertThat(outboxCount(payment.orderId(), "PaymentSucceeded")).isZero();
        assertThat(reconciliation.run().checked())
                .as("checked payments rest for the stale period")
                .isZero();
    }

    @Test
    void aPaymentThatChangedRecentlyIsNotChecked() {
        Initiated payment = initiated("pi_it_rec_fresh");
        // the stubbed PaymentIntent is weeks old; this payment changed a minute ago
        jdbc.sql("UPDATE payment SET updated_at = :at WHERE id = :id")
                .param("at", java.sql.Timestamp.from(clock.instant().minus(Duration.ofMinutes(1))))
                .param("id", payment.paymentId())
                .update();

        assertThat(reconciliation.run().checked()).isZero();
        assertThat(TestStripe.requests().stream()
                        .filter(r -> r.getMethod().getName().equals("GET")))
                .isEmpty();
    }

    @Test
    @DisplayName("Stripe unavailable: the run does not fail, and the next run finishes the job")
    void anUnavailableStripeIsCaughtUpByTheNextRun() {
        Initiated payment = initiated("pi_it_rec_3");
        TestStripe.stubRetrieveFailing(503);
        clock.advance(Duration.ofMinutes(11));

        ReconciliationSummary failed = reconciliation.run();

        assertThat(failed.failed()).isEqualTo(1);
        assertThat(statusOf(payment.paymentId())).isEqualTo("REQUIRES_PAYMENT_METHOD");

        TestStripe.reset();
        TestStripe.stubRetrieve(payment.paymentIntentId(), "succeeded", 3097, "EUR");
        ReconciliationSummary next = reconciliation.run();

        assertThat(next.drifted()).isEqualTo(1);
        assertThat(statusOf(payment.paymentId())).isEqualTo("SUCCEEDED");
        assertThat(outboxCount(payment.orderId(), "PaymentSucceeded")).isEqualTo(1);
    }

    @Test
    @DisplayName("F21: a webhook and reconciliation applying the same success at once yield one transition, one event")
    void aWebhookAndReconciliationAtOnceYieldOneEvent() throws Exception {
        Initiated payment = initiated("pi_it_rec_race");
        TestStripe.stubRetrieveSlowly(payment.paymentIntentId(), "succeeded", Duration.ofMillis(1500));
        clock.advance(Duration.ofMinutes(11));

        CompletableFuture<ReconciliationSummary> running = CompletableFuture.supplyAsync(reconciliation::run);
        deliverSucceeded(payment);
        processor.run();
        ReconciliationSummary summary = running.get();

        assertThat(statusOf(payment.paymentId())).isEqualTo("SUCCEEDED");
        assertThat(outboxCount(payment.orderId(), "PaymentSucceeded"))
                .as("whichever applied first, the other found nothing to do")
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM payment_status_history WHERE payment_id = :id AND to_status = "
                                + "'SUCCEEDED'")
                        .param("id", payment.paymentId())
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        assertThat(summary.failed()).isZero();
    }

    @Test
    void theAdminApiNeedsTheOpsRole() {
        assertThat(post("/admin/reconciliation/run", TestKeycloak.tokenOf("customer1"))
                        .status())
                .isEqualTo(403);
        assertThat(get("/admin/reconciliation/last", null).status()).isEqualTo(401);
    }
}
