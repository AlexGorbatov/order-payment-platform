package com.altronixsoft.opp.payment.adapter.out.stripe;

import com.altronixsoft.opp.payment.application.StripeNotification;
import com.altronixsoft.opp.payment.application.WebhookPayloadParser;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link WebhookPayloadParser} for Stripe snapshot events. It reads {@code data.object} as plain JSON rather than
 * through the SDK's typed objects, so an event rendered with another API version than the SDK's still works as long as
 * the few fields used here exist (they have been stable for years). Event types and object shapes as documented at
 * docs.stripe.com/api/events/types.
 *
 * <p>Codes must look like Stripe codes ({@code [a-z0-9_]}, at most 64 characters) or are dropped; free text loses
 * control characters, anything that looks like a card number or a secret, and is cut to 500 characters.
 */
final class StripeWebhookPayloadParser implements WebhookPayloadParser {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern CODE = Pattern.compile("[a-z0-9_]{1,64}");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}\\s]+");
    private static final Pattern CARD_NUMBER = Pattern.compile("\\b(?:\\d[ -]?){12,18}\\d\\b");

    @Override
    public StripeNotification parse(String type, String payload) {
        return switch (type) {
            case "payment_intent.processing",
                    "payment_intent.requires_action",
                    "payment_intent.payment_failed",
                    "payment_intent.succeeded",
                    "payment_intent.canceled" -> paymentIntent(object(payload, type));
            case "charge.refunded" -> {
                JsonNode charge = object(payload, type);
                yield new StripeNotification.ChargeRefunded(
                        required(charge, "payment_intent", type),
                        charge.path("refunded").asBoolean(false));
            }
            case "refund.failed" -> {
                JsonNode refund = object(payload, type);
                yield new StripeNotification.RefundFailed(
                        required(refund, "id", type),
                        text(refund, "payment_intent"),
                        text(refund.path("metadata"), "refundId"),
                        orDefault(code(text(refund, "failure_reason")), "unknown"));
            }
            case "charge.dispute.created" -> {
                JsonNode dispute = object(payload, type);
                yield new StripeNotification.DisputeCreated(
                        required(dispute, "id", type),
                        required(dispute, "payment_intent", type),
                        orDefault(code(text(dispute, "reason")), "general"));
            }
            default -> new StripeNotification.Unsupported(type);
        };
    }

    private static StripeNotification.PaymentIntentChanged paymentIntent(JsonNode intent) {
        JsonNode error = intent.path("last_payment_error");
        StripeNotification.PaymentError lastPaymentError = error.isObject()
                ? new StripeNotification.PaymentError(
                        code(text(error, "code")), code(text(error, "decline_code")), message(text(error, "message")))
                : null;
        return new StripeNotification.PaymentIntentChanged(
                required(intent, "id", "payment_intent"),
                text(intent.path("metadata"), "paymentId"),
                required(intent, "status", "payment_intent"),
                code(text(intent, "cancellation_reason")),
                lastPaymentError);
    }

    private static JsonNode object(String payload, String type) {
        JsonNode object;
        try {
            object = JSON.readTree(payload).path("data").path("object");
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Webhook payload of type " + type + " is not JSON", e);
        }
        if (!object.isObject()) {
            throw new IllegalArgumentException("Webhook payload of type " + type + " has no data.object");
        }
        return object;
    }

    private static String required(JsonNode node, String field, String type) {
        String value = text(node, field);
        if (value == null) {
            throw new IllegalArgumentException("data.object." + field + " is missing in a " + type + " event");
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString() || value.stringValue().isBlank()) {
            return null;
        }
        return value.stringValue();
    }

    static String code(String value) {
        return value != null && CODE.matcher(value).matches() ? value : null;
    }

    static String message(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = CONTROL.matcher(value).replaceAll(" ").strip();
        cleaned = CARD_NUMBER.matcher(cleaned).replaceAll("[redacted]");
        cleaned = Redactor.redact(cleaned);
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static String orDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
