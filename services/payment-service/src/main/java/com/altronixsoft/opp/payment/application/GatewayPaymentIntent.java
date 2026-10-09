package com.altronixsoft.opp.payment.application;

import com.altronixsoft.opp.payment.domain.Money;
import java.time.Instant;

/**
 * What the platform needs to know about a PaymentIntent at the provider, without the provider's types.
 *
 * @param id the provider's id ({@code pi_...})
 * @param status the provider's status string, to be mapped by the domain
 *     ({@link com.altronixsoft.opp.payment.domain.StripePaymentIntentStatus})
 * @param created when the provider created it
 * @param clientSecret lets the browser complete the payment; a secret: never logged, never stored, shown only to the
 *     paying customer
 * @param lastErrorCode the provider's code of the last failed attempt, or {@code null}
 * @param lastErrorDeclineCode the card network's decline code of the last failed attempt, or {@code null}
 * @param lastErrorMessage the provider's message about the last failed attempt, or {@code null}
 */
public record GatewayPaymentIntent(
        String id,
        String status,
        Money amount,
        Instant created,
        String clientSecret,
        String lastErrorCode,
        String lastErrorDeclineCode,
        String lastErrorMessage) {

    @Override
    public String toString() {
        return "GatewayPaymentIntent[id=" + id + ", status=" + status + ", amount=" + amount + ", created=" + created
                + ", clientSecret=" + (clientSecret == null ? "none" : "<redacted>") + ", lastErrorCode="
                + lastErrorCode
                + "]";
    }
}
