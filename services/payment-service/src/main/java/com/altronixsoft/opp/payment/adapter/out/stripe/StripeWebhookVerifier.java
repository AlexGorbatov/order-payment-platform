package com.altronixsoft.opp.payment.adapter.out.stripe;

import com.altronixsoft.opp.payment.application.InvalidWebhookException;
import com.altronixsoft.opp.payment.application.VerifiedWebhookEvent;
import com.altronixsoft.opp.payment.application.WebhookVerifier;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import java.time.Clock;
import java.time.Instant;

/**
 * {@link WebhookVerifier} with the SDK's {@link Webhook#constructEvent}: HMAC-SHA256 over {@code t + "." + body}, compared
 * in constant time with every {@code v1} signature of the header, and the timestamp checked against the injected clock
 * with the configured tolerance (architecture §8.3).
 *
 * <p>Every configured secret is tried in turn. While a secret is rolled, Stripe signs each delivery with the old and the
 * new secret, so either one being configured is enough, and both may be configured at once.
 */
final class StripeWebhookVerifier implements WebhookVerifier {

    private final StripeProperties.Webhook settings;
    private final Clock clock;

    StripeWebhookVerifier(StripeProperties.Webhook settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public VerifiedWebhookEvent verify(String payload, String signatureHeader) {
        if (settings.signingSecrets().isEmpty()) {
            throw new InvalidWebhookException(
                    InvalidWebhookException.Reason.NOT_CONFIGURED,
                    "No webhook signing secret is configured (STRIPE_WEBHOOK_SECRET); every webhook is refused");
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new InvalidWebhookException(
                    InvalidWebhookException.Reason.MISSING_SIGNATURE, "The Stripe-Signature header is missing");
        }
        Event event = null;
        SignatureVerificationException lastFailure = null;
        for (String secret : settings.signingSecrets()) {
            try {
                event = Webhook.constructEvent(
                        payload, signatureHeader, secret, settings.tolerance().toSeconds(), clock);
                break;
            } catch (SignatureVerificationException e) {
                lastFailure = e;
            } catch (RuntimeException e) {
                // the signature matched (it is checked first), but the body is not an event
                throw new InvalidWebhookException(
                        InvalidWebhookException.Reason.MALFORMED_PAYLOAD,
                        "A correctly signed webhook is not a Stripe event: "
                                + e.getClass().getSimpleName());
            }
        }
        if (event == null) {
            throw new InvalidWebhookException(
                    InvalidWebhookException.Reason.INVALID_SIGNATURE,
                    "Webhook signature rejected: "
                            + (lastFailure == null ? "no secret matched" : Redactor.redact(lastFailure.getMessage())));
        }
        if (isBlank(event.getId()) || isBlank(event.getType()) || event.getCreated() == null) {
            throw new InvalidWebhookException(
                    InvalidWebhookException.Reason.MALFORMED_PAYLOAD,
                    "A correctly signed webhook lacks its id, type or created");
        }
        return new VerifiedWebhookEvent(
                event.getId(),
                event.getType(),
                event.getApiVersion(),
                Boolean.TRUE.equals(event.getLivemode()),
                Instant.ofEpochSecond(event.getCreated()));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
