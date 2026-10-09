package com.altronixsoft.opp.payment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders the synthetic Stripe event templates of {@code src/test/resources/stripe/events}. Every placeholder must be
 * filled; ids that do not matter for a test get unique defaults.
 */
public final class StripeEvents {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Z_]+)}}");

    private final String type;
    private final Map<String, String> values = new LinkedHashMap<>();

    private StripeEvents(String type) {
        this.type = type;
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        values.put("EVENT_ID", "evt_synthetic_" + suffix);
        values.put("SUFFIX", suffix);
        values.put("LIVEMODE", "false");
        values.put("AMOUNT", "3097");
        values.put("CURRENCY", "eur");
        values.put("ORDER_ID", UUID.randomUUID().toString());
        values.put("PAYMENT_ID", UUID.randomUUID().toString());
        values.put("REFUND_ID", UUID.randomUUID().toString());
        values.put("STRIPE_REFUND_ID", "re_synthetic_" + suffix);
    }

    /** Starts an event of {@code type}, e.g. {@code payment_intent.succeeded}. */
    public static StripeEvents event(String type) {
        return new StripeEvents(type);
    }

    public StripeEvents id(String eventId) {
        return with("EVENT_ID", eventId);
    }

    public StripeEvents created(Instant created) {
        return with("CREATED", Long.toString(created.getEpochSecond()));
    }

    public StripeEvents paymentIntent(String paymentIntentId) {
        return with("PAYMENT_INTENT_ID", paymentIntentId);
    }

    public StripeEvents payment(UUID paymentId) {
        return with("PAYMENT_ID", paymentId.toString());
    }

    public StripeEvents livemode(boolean livemode) {
        return with("LIVEMODE", Boolean.toString(livemode));
    }

    public StripeEvents with(String placeholder, String value) {
        values.put(placeholder, value);
        return this;
    }

    public String eventId() {
        return values.get("EVENT_ID");
    }

    public String render() {
        String template;
        try (InputStream in = StripeEvents.class.getResourceAsStream("/stripe/events/" + type + ".json")) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture for " + type);
            }
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            if (value == null) {
                throw new IllegalStateException("Fixture " + type + " needs a value for {{" + matcher.group(1) + "}}");
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
