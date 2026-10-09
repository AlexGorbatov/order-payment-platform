package com.altronixsoft.opp.payment.application;

/**
 * Port: checks that a webhook request really comes from Stripe (architecture §8.3, ADR-0009) and reads its envelope.
 * The implementation knows the signing secrets; several may be valid at once while one is being rotated.
 */
public interface WebhookVerifier {

    /**
     * @param payload the request body exactly as received, before any parsing
     * @param signatureHeader the {@code Stripe-Signature} header, may be {@code null}
     * @throws InvalidWebhookException the signature does not match any secret, its timestamp is outside the tolerance,
     *     or the body is not a Stripe event
     */
    VerifiedWebhookEvent verify(String payload, String signatureHeader);
}
