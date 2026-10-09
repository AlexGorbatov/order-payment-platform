package com.altronixsoft.opp.payment.adapter.out.stripe;

import java.util.regex.Pattern;

/**
 * Refuses to start with anything but a test-mode key (architecture §8.2, ADR-0012): this platform must be impossible to
 * run against live money. Accepts {@code sk_test_…} and {@code rk_test_…} (a restricted test key); a live key, a
 * publishable key, a malformed key and a missing key all stop the application with a message that says what to do.
 * Only the {@code sk_live_}-style prefix of a refused key is ever put into a message.
 */
public final class LiveModeGuard {

    private static final Pattern TEST_KEY = Pattern.compile("^(sk|rk)_test_[A-Za-z0-9_]+$");
    private static final Pattern KEY_PREFIX = Pattern.compile("^((?:sk|rk|pk)_(?:live|test)_).*");

    private LiveModeGuard() {}

    /**
     * @return a guard instance, to give the beans that need a verified key something to depend on
     * @throws InvalidStripeKeyException the key is missing or is not a test-mode secret key
     */
    public static LiveModeGuard verify(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new InvalidStripeKeyException(
                    InvalidStripeKeyException.Reason.MISSING,
                    null,
                    "stripe.api-key (environment variable STRIPE_API_KEY) is not set. "
                            + "Set a Stripe TEST-mode secret key (sk_test_...); any sk_test_ value works against stripe-mock.");
        }
        if (!TEST_KEY.matcher(apiKey).matches()) {
            var prefix = KEY_PREFIX.matcher(apiKey);
            String shown = prefix.matches() ? prefix.group(1) : null;
            throw new InvalidStripeKeyException(
                    InvalidStripeKeyException.Reason.NOT_A_TEST_KEY,
                    shown,
                    "Refusing to start: the Stripe API key "
                            + (shown == null ? "(unrecognised format)" : "starting with '" + shown + "'")
                            + " is not a test-mode secret key. This platform only runs against Stripe TEST mode; "
                            + "use a key starting with sk_test_ or rk_test_.");
        }
        return new LiveModeGuard();
    }
}
