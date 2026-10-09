package com.altronixsoft.opp.payment.adapter.out.stripe;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Turns {@link InvalidStripeKeyException} into Spring Boot's readable "APPLICATION FAILED TO START" report. */
public class LiveModeFailureAnalyzer extends AbstractFailureAnalyzer<InvalidStripeKeyException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, InvalidStripeKeyException cause) {
        String description = cause.getMessage();
        String action = cause.reason() == InvalidStripeKeyException.Reason.MISSING
                ? "Set STRIPE_API_KEY in the environment (copy .env.example to .env), for example to sk_test_replace_me "
                        + "when running against stripe-mock."
                : "Replace the key with a TEST-mode secret key from https://dashboard.stripe.com/test/apikeys "
                        + "(it must start with sk_test_ or rk_test_). Live keys are never accepted.";
        return new FailureAnalysis(description, action, cause);
    }
}
