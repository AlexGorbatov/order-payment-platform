package com.altronixsoft.opp.payment.adapter.out.stripe;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the Stripe adapter (prefix {@code stripe}). The API key comes from the environment only
 * ({@code STRIPE_API_KEY}); it is never logged and never part of {@link #toString()}.
 *
 * @param apiKey the secret API key; only test-mode keys ({@code sk_test_}, {@code rk_test_}) are accepted
 * @param apiBase base URL of the API, for stripe-mock ({@code http://localhost:12111}); empty means the real Stripe
 * @param connectTimeout how long to wait for the TCP connection
 * @param readTimeout how long to wait for the response; the SDK retries a timeout, so a call may take up to
 *     {@code (maxNetworkRetries + 1)} times this
 * @param maxNetworkRetries retries by the SDK on connection errors, timeouts, 409 and 5xx, always with the same
 *     idempotency key
 * @param webhook verification of incoming webhooks
 */
@ConfigurationProperties("stripe")
public record StripeProperties(
        String apiKey,
        String apiBase,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("15s") Duration readTimeout,
        @DefaultValue("2") int maxNetworkRetries,
        @DefaultValue CircuitBreakerProperties circuitBreaker,
        @DefaultValue Webhook webhook) {

    /**
     * Webhook signature verification (architecture §8.3, ADR-0009). Secrets come from the environment only
     * ({@code STRIPE_WEBHOOK_SECRET}, comma-separated) and are never logged.
     *
     * @param signingSecrets every secret currently valid for the endpoint: one normally, two while a secret is rolled
     *     (Stripe signs with each until the old one expires). Empty: every webhook is refused.
     * @param tolerance how old the signed timestamp may be: older requests are replays and are refused (F12)
     */
    public record Webhook(
            @DefaultValue List<String> signingSecrets,
            @DefaultValue("300s") Duration tolerance) {

        public Webhook {
            signingSecrets = signingSecrets == null
                    ? List.of()
                    : signingSecrets.stream()
                            .filter(secret -> secret != null && !secret.isBlank())
                            .map(String::strip)
                            .toList();
        }

        @Override
        public String toString() {
            return "Webhook[signingSecrets=<" + signingSecrets.size() + " redacted>, tolerance=" + tolerance + "]";
        }
    }

    /**
     * The circuit breaker around the gateway. Only {@code TRANSIENT} failures count (architecture §8.2): a rejected
     * request or a declined card is a healthy Stripe.
     *
     * @param slidingWindowSize number of most recent calls the failure rate is computed over
     * @param minimumNumberOfCalls calls needed before the breaker may open
     * @param failureRateThreshold percentage of transient failures that opens the breaker
     * @param waitDurationInOpenState how long calls are refused before a trial
     * @param permittedCallsInHalfOpenState trial calls that decide whether to close again
     */
    public record CircuitBreakerProperties(
            @DefaultValue("20") int slidingWindowSize,
            @DefaultValue("10") int minimumNumberOfCalls,
            @DefaultValue("50") float failureRateThreshold,
            @DefaultValue("30s") Duration waitDurationInOpenState,
            @DefaultValue("3") int permittedCallsInHalfOpenState) {}

    @Override
    public String toString() {
        return "StripeProperties[apiKey=<redacted>, apiBase=" + apiBase + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + ", maxNetworkRetries=" + maxNetworkRetries + ", circuitBreaker="
                + circuitBreaker + ", webhook=" + webhook + "]";
    }
}
