package com.altronixsoft.opp.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.altronixsoft.opp.payment.domain.StripeWebhookEvent;
import com.altronixsoft.opp.payment.domain.WebhookEventStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verify, persist, acknowledge (architecture §6.6): nothing else happens on receipt. */
class ReceiveWebhookServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00.123456789Z");
    private static final Instant CREATED = Instant.parse("2026-10-09T11:59:58Z");

    private final InMemoryWebhookEvents events = new InMemoryWebhookEvents();

    private ReceiveWebhookService service(WebhookVerifier verifier) {
        return new ReceiveWebhookService(verifier, events, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static WebhookVerifier verifying(boolean livemode) {
        return (payload, header) ->
                new VerifiedWebhookEvent("evt_1", "payment_intent.succeeded", "2026-09-30.endive", livemode, CREATED);
    }

    @Test
    void aVerifiedEventIsStoredAsReceivedAndDueAtOnce() {
        ReceiveWebhookService.Received received = service(verifying(false)).receive("{\"id\":\"evt_1\"}", "t=1,v1=x");

        assertThat(received).isEqualTo(new ReceiveWebhookService.Received("evt_1", "payment_intent.succeeded", false));
        StripeWebhookEvent stored = events.get("evt_1");
        assertThat(stored.status()).isEqualTo(WebhookEventStatus.RECEIVED);
        assertThat(stored.payload()).isEqualTo("{\"id\":\"evt_1\"}");
        assertThat(stored.stripeCreatedAt()).isEqualTo(CREATED);
        assertThat(stored.receivedAt()).isEqualTo(Instant.parse("2026-10-09T12:00:00.123456Z"));
        assertThat(stored.nextAttemptAt()).isEqualTo(stored.receivedAt());
    }

    @Test
    @DisplayName("F09: a redelivery is acknowledged and changes nothing")
    void aRedeliveryIsADuplicate() {
        ReceiveWebhookService service = service(verifying(false));
        service.receive("{\"first\":true}", "sig");

        ReceiveWebhookService.Received again = service.receive("{\"second\":true}", "sig");

        assertThat(again.duplicate()).isTrue();
        assertThat(events.get("evt_1").payload()).isEqualTo("{\"first\":true}");
    }

    @Test
    @DisplayName("F12: an unverifiable request stores nothing")
    void anInvalidSignatureStoresNothing() {
        WebhookVerifier refusing = (payload, header) -> {
            throw new InvalidWebhookException(InvalidWebhookException.Reason.INVALID_SIGNATURE, "no");
        };

        assertThatThrownBy(() -> service(refusing).receive("{}", "sig")).isInstanceOf(InvalidWebhookException.class);
        assertThat(events.findById("evt_1")).isEmpty();
    }

    @Test
    @DisplayName("F13: a live-mode event is refused and not stored")
    void aLiveModeEventIsRefused() {
        assertThatThrownBy(() -> service(verifying(true)).receive("{}", "sig"))
                .isInstanceOfSatisfying(LiveModeWebhookException.class, e -> {
                    assertThat(e.eventId()).isEqualTo("evt_1");
                    assertThat(e.type()).isEqualTo("payment_intent.succeeded");
                });
        assertThat(events.findById("evt_1")).isEmpty();
    }
}
