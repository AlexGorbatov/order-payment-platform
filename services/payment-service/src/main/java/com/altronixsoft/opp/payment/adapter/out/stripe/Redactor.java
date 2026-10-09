package com.altronixsoft.opp.payment.adapter.out.stripe;

import java.util.regex.Pattern;

/**
 * Removes secrets from text before it is logged or stored: Stripe API keys, webhook signing secrets and PaymentIntent /
 * SetupIntent client secrets. Stripe's own messages are normally clean; this is the belt to their braces.
 */
final class Redactor {

    private static final Pattern API_KEY = Pattern.compile("\\b((?:sk|rk|pk)_(?:test|live)_)[A-Za-z0-9_*]+");
    private static final Pattern WEBHOOK_SECRET = Pattern.compile("\\bwhsec_[A-Za-z0-9_*]+");
    private static final Pattern CLIENT_SECRET = Pattern.compile("\\b((?:pi|seti)_[A-Za-z0-9]+)_secret_[A-Za-z0-9_*]+");
    private static final int MAX_LENGTH = 500;

    private Redactor() {}

    static String redact(String text) {
        if (text == null) {
            return null;
        }
        String result = API_KEY.matcher(text).replaceAll("$1***");
        result = WEBHOOK_SECRET.matcher(result).replaceAll("whsec_***");
        result = CLIENT_SECRET.matcher(result).replaceAll("$1_secret_***");
        return result.length() <= MAX_LENGTH ? result : result.substring(0, MAX_LENGTH);
    }
}
