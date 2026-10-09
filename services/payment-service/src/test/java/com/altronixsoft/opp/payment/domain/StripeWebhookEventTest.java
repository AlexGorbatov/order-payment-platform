package com.altronixsoft.opp.payment.domain;

import static com.altronixsoft.opp.payment.domain.PaymentFixtures.at;
import static com.altronixsoft.opp.payment.domain.PaymentFixtures.fixedRandom;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class StripeWebhookEventTest {

    private static final RetryPolicy POLICY = new RetryPolicy(Duration.ofSeconds(2), Duration.ofMinutes(5), 2, 0.2);

    private static StripeWebhookEvent received() {
        return StripeWebhookEvent.receive(
                "evt_1", "payment_intent.succeeded", "2025-01-27.acacia", false, at(-5), "{\"id\":\"evt_1\"}", at(0));
    }

    @Test
    void aReceivedEventIsDueImmediately() {
        StripeWebhookEvent event = received();

        assertThat(event.eventId()).isEqualTo("evt_1");
        assertThat(event.type()).isEqualTo("payment_intent.succeeded");
        assertThat(event.apiVersion()).isEqualTo("2025-01-27.acacia");
        assertThat(event.livemode()).isFalse();
        assertThat(event.stripeCreatedAt()).isEqualTo(at(-5));
        assertThat(event.payload()).isEqualTo("{\"id\":\"evt_1\"}");
        assertThat(event.status()).isEqualTo(WebhookEventStatus.RECEIVED);
        assertThat(event.attempts()).isZero();
        assertThat(event.nextAttemptAt()).isEqualTo(at(0));
        assertThat(event.receivedAt()).isEqualTo(at(0));
        assertThat(event.processedAt()).isNull();
        assertThat(event.lastError()).isNull();
    }

    @Test
    void aLiveModeEventIsNeverStored() {
        assertThatThrownBy(() -> StripeWebhookEvent.receive("evt_live", "x", null, true, at(0), "{}", at(0)))
                .isInstanceOf(InvalidPaymentException.class)
                .hasMessageContaining("evt_live");
    }

    @Test
    void anEventNeedsAnIdAndATypeAndABody() {
        assertThatThrownBy(() -> StripeWebhookEvent.receive(" ", "x", null, false, at(0), "{}", at(0)))
                .isInstanceOf(InvalidPaymentException.class);
        assertThatThrownBy(() -> StripeWebhookEvent.receive(null, "x", null, false, at(0), "{}", at(0)))
                .isInstanceOf(InvalidPaymentException.class);
        assertThatThrownBy(() -> StripeWebhookEvent.receive("e", "", null, false, at(0), "{}", at(0)))
                .isInstanceOf(InvalidPaymentException.class);
        assertThatThrownBy(() -> StripeWebhookEvent.receive("e", "x", null, false, at(0), null, at(0)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> StripeWebhookEvent.receive("e", "x", null, false, null, "{}", at(0)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void processedIgnoredAndStaleAreFinal() {
        StripeWebhookEvent processed = received();
        processed.markProcessed(at(3));
        assertThat(processed.status()).isEqualTo(WebhookEventStatus.PROCESSED);
        assertThat(processed.processedAt()).isEqualTo(at(3));
        assertThat(processed.nextAttemptAt()).isNull();

        StripeWebhookEvent ignored = received();
        ignored.markIgnored(at(3));
        assertThat(ignored.status()).isEqualTo(WebhookEventStatus.IGNORED);

        StripeWebhookEvent stale = received();
        stale.markStaleIgnored(at(3));
        assertThat(stale.status()).isEqualTo(WebhookEventStatus.STALE_IGNORED);
    }

    @ParameterizedTest
    @EnumSource(
            value = WebhookEventStatus.class,
            names = {"PROCESSED", "IGNORED", "STALE_IGNORED", "DEAD"})
    void aFinishedEventIsNotTouchedAgain(WebhookEventStatus status) {
        StripeWebhookEvent event =
                StripeWebhookEvent.restore("e", "t", null, false, at(0), "{}", status, 0, null, null, at(0), at(1));

        assertThatThrownBy(() -> event.markProcessed(at(2))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> event.leaseUntil(at(2))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> event.scheduleRetry(at(2), POLICY, fixedRandom(0), "e"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(event.status()).isEqualTo(status);
    }

    @Test
    void failuresBackOffAndEndDead() {
        StripeWebhookEvent event = received();

        assertThat(event.scheduleRetry(at(10), POLICY, fixedRandom(0), "no payment for pi_1"))
                .isEqualTo(RetryDecision.RETRY_SCHEDULED);
        assertThat(event.status()).isEqualTo(WebhookEventStatus.FAILED);
        assertThat(event.attempts()).isEqualTo(1);
        assertThat(event.nextAttemptAt()).isEqualTo(at(12));
        assertThat(event.lastError()).isEqualTo("no payment for pi_1");

        assertThat(event.scheduleRetry(at(20), POLICY, fixedRandom(0), "e".repeat(5000)))
                .isEqualTo(RetryDecision.EXHAUSTED);
        assertThat(event.status()).isEqualTo(WebhookEventStatus.DEAD);
        assertThat(event.nextAttemptAt()).isNull();
        assertThat(event.processedAt()).isEqualTo(at(20));
        assertThat(event.lastError()).hasSize(1024);
    }

    @Test
    void aFailedEventCanStillBeFinished() {
        StripeWebhookEvent event = received();
        event.scheduleRetry(at(10), POLICY, fixedRandom(0), "e");

        event.markProcessed(at(13));

        assertThat(event.status()).isEqualTo(WebhookEventStatus.PROCESSED);
    }

    @Test
    void aLeasePostponesTheEvent() {
        StripeWebhookEvent event = received();

        event.leaseUntil(at(60));

        assertThat(event.nextAttemptAt()).isEqualTo(at(60));
        assertThat(event.status()).isEqualTo(WebhookEventStatus.RECEIVED);
    }
}
