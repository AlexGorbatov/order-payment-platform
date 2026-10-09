package com.altronixsoft.opp.payment.application;

/**
 * Port: reads the part of a stored webhook payload the platform acts on ({@code data.object}) into a
 * {@link StripeNotification}. Free text from Stripe (error messages, reasons) comes back sanitized: no secrets, no
 * control characters, bounded length.
 */
public interface WebhookPayloadParser {

    /**
     * @param type the event type
     * @param payload the stored event JSON
     * @return the notification; {@link StripeNotification.Unsupported} for a type the platform does not handle
     * @throws IllegalArgumentException a handled type whose object lacks what the platform needs
     */
    StripeNotification parse(String type, String payload);
}
