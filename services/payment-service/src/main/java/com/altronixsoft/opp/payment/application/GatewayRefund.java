package com.altronixsoft.opp.payment.application;

import com.altronixsoft.opp.payment.domain.Money;
import java.time.Instant;

/**
 * A refund at the provider.
 *
 * @param id the provider's id ({@code re_...})
 * @param status the provider's status string ({@code pending}, {@code succeeded}, {@code failed}, ...)
 * @param paymentIntentId the refunded PaymentIntent
 * @param failureReason the provider's reason if the refund failed, or {@code null}
 */
public record GatewayRefund(
        String id, String status, Money amount, String paymentIntentId, Instant created, String failureReason) {}
