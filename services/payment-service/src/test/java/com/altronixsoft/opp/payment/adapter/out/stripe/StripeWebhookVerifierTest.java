package com.altronixsoft.opp.payment.adapter.out.stripe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.altronixsoft.opp.payment.StripeEvents;
import com.altronixsoft.opp.payment.StripeWebhookTestSigner;
import com.altronixsoft.opp.payment.application.InvalidWebhookException;
import com.altronixsoft.opp.payment.application.InvalidWebhookException.Reason;
import com.altronixsoft.opp.payment.application.VerifiedWebhookEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Signature verification with the SDK (architecture §8.3): fresh, correctly signed events only. */
class StripeWebhookVerifierTest {

    private static final String SECRET = "whsec_unit_current";
    private static final String OLD_SECRET = "whsec_unit_previous";
    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");

    private static StripeWebhookVerifier verifier(String... secrets) {
        return new StripeWebhookVerifier(
                new StripeProperties.Webhook(List.of(secrets), Duration.ofSeconds(300)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static String payload(boolean livemode) {
        return StripeEvents.event("payment_intent.succeeded")
                .id("evt_unit_1")
                .created(NOW.minusSeconds(2))
                .paymentIntent("pi_unit_1")
                .livemode(livemode)
                .render();
    }

    @Test
    void aCorrectlySignedFreshEventIsAccepted() {
        String payload = payload(false);

        VerifiedWebhookEvent event =
                verifier(SECRET).verify(payload, StripeWebhookTestSigner.sign(payload, NOW, SECRET));

        assertThat(event.eventId()).isEqualTo("evt_unit_1");
        assertThat(event.type()).isEqualTo("payment_intent.succeeded");
        assertThat(event.apiVersion()).isEqualTo("2026-09-30.endive");
        assertThat(event.livemode()).isFalse();
        assertThat(event.createdAt()).isEqualTo(NOW.minusSeconds(2));
    }

    @Test
    @DisplayName("F12: a signature made with another secret is refused")
    void aForeignSignatureIsRefused() {
        String payload = payload(false);

        assertThatThrownBy(() ->
                        verifier(SECRET).verify(payload, StripeWebhookTestSigner.sign(payload, NOW, "whsec_attacker")))
                .isInstanceOfSatisfying(
                        InvalidWebhookException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.INVALID_SIGNATURE));
    }

    @Test
    @DisplayName("F12: a body changed after signing is refused")
    void aTamperedBodyIsRefused() {
        String payload = payload(false);
        String header = StripeWebhookTestSigner.sign(payload, NOW, SECRET);

        assertThatThrownBy(() -> verifier(SECRET).verify(payload.replace("3097", "1"), header))
                .isInstanceOfSatisfying(
                        InvalidWebhookException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.INVALID_SIGNATURE));
    }

    @Test
    @DisplayName("F12: a replay signed more than 5 minutes ago is refused")
    void anOldTimestampIsRefused() {
        String payload = payload(false);

        assertThatThrownBy(() -> verifier(SECRET)
                        .verify(payload, StripeWebhookTestSigner.sign(payload, NOW.minusSeconds(301), SECRET)))
                .isInstanceOfSatisfying(
                        InvalidWebhookException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.INVALID_SIGNATURE));
        assertThat(verifier(SECRET)
                        .verify(payload, StripeWebhookTestSigner.sign(payload, NOW.minusSeconds(299), SECRET))
                        .eventId())
                .isEqualTo("evt_unit_1");
    }

    @Test
    void duringARotationEitherSecretVerifies() {
        String payload = payload(false);
        StripeWebhookVerifier rotating = verifier(OLD_SECRET, SECRET);

        assertThat(rotating.verify(payload, StripeWebhookTestSigner.sign(payload, NOW, SECRET)))
                .isNotNull();
        assertThat(rotating.verify(payload, StripeWebhookTestSigner.sign(payload, NOW, OLD_SECRET)))
                .isNotNull();
        assertThat(verifier(SECRET).verify(payload, StripeWebhookTestSigner.sign(payload, NOW, OLD_SECRET, SECRET)))
                .as("Stripe signs with both secrets while one is rolled")
                .isNotNull();
    }

    @Test
    void aMissingHeaderOrNoSecretIsRefused() {
        String payload = payload(false);

        assertThatThrownBy(() -> verifier(SECRET).verify(payload, null))
                .isInstanceOfSatisfying(
                        InvalidWebhookException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.MISSING_SIGNATURE));
        assertThatThrownBy(() -> verifier().verify(payload, StripeWebhookTestSigner.sign(payload, NOW, SECRET)))
                .isInstanceOfSatisfying(
                        InvalidWebhookException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.NOT_CONFIGURED));
    }

    @Test
    void aCorrectlySignedBodyThatIsNoEventIsMalformed() {
        String notJson = "not json at all";
        String noId = "{\"object\":\"event\",\"type\":\"payment_intent.succeeded\",\"created\":1}";

        assertThatThrownBy(() -> verifier(SECRET).verify(notJson, StripeWebhookTestSigner.sign(notJson, NOW, SECRET)))
                .isInstanceOfSatisfying(
                        InvalidWebhookException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.MALFORMED_PAYLOAD));
        assertThatThrownBy(() -> verifier(SECRET).verify(noId, StripeWebhookTestSigner.sign(noId, NOW, SECRET)))
                .isInstanceOfSatisfying(
                        InvalidWebhookException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.MALFORMED_PAYLOAD));
    }

    @Test
    void theLiveModeFlagIsReported() {
        String payload = payload(true);

        assertThat(verifier(SECRET)
                        .verify(payload, StripeWebhookTestSigner.sign(payload, NOW, SECRET))
                        .livemode())
                .isTrue();
    }

    @Test
    void secretsAreNeverPrinted() {
        StripeProperties.Webhook settings =
                new StripeProperties.Webhook(List.of(SECRET, " ", ""), Duration.ofSeconds(1));

        assertThat(settings.signingSecrets()).containsExactly(SECRET);
        assertThat(settings.toString()).doesNotContain("whsec_").contains("1 redacted");
    }
}
