package com.altronixsoft.opp.payment;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.StringJoiner;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs a webhook body the way Stripe does (docs.stripe.com/webhooks, "verify manually"): {@code Stripe-Signature:
 * t=<unix seconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>}, with one {@code v1} per secret while a secret is
 * rolled. Test code only; the local equivalent for a running service is {@code scripts/send-test-webhook.sh}.
 */
public final class StripeWebhookTestSigner {

    private StripeWebhookTestSigner() {}

    /** The {@code Stripe-Signature} header for {@code payload}, signed at {@code timestamp} with every secret given. */
    public static String sign(String payload, Instant timestamp, String... secrets) {
        long t = timestamp.getEpochSecond();
        StringJoiner header = new StringJoiner(",").add("t=" + t);
        for (String secret : secrets) {
            header.add("v1=" + hmacSha256(secret, t + "." + payload));
        }
        return header.toString();
    }

    static String hmacSha256(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
