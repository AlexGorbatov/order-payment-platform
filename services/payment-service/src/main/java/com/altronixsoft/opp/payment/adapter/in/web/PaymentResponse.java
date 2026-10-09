package com.altronixsoft.opp.payment.adapter.in.web;

import com.altronixsoft.opp.payment.application.PaymentView;
import com.altronixsoft.opp.payment.domain.Payment;
import com.altronixsoft.opp.payment.domain.PaymentStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/**
 * The payment of an order as the API shows it.
 *
 * @param clientSecret the secret the browser needs to complete the payment at Stripe. Present only for the paying
 *     customer and only while the PaymentIntent waits for a payment method or an action; absent otherwise. A secret: the
 *     response is {@code Cache-Control: no-store}, and it is never stored or logged by the service.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaymentResponse(
        UUID paymentId,
        UUID orderId,
        PaymentStatus status,
        MoneyResponse amount,
        String stripePaymentIntentId,
        String clientSecret,
        String lastErrorCode,
        boolean disputed,
        Instant createdAt,
        Instant updatedAt) {

    static PaymentResponse of(PaymentView view) {
        Payment payment = view.payment();
        return new PaymentResponse(
                payment.id(),
                payment.orderId(),
                payment.status(),
                MoneyResponse.of(payment.amount()),
                payment.stripePaymentIntentId(),
                view.clientSecret(),
                payment.lastErrorCode(),
                payment.disputed(),
                payment.createdAt(),
                payment.updatedAt());
    }

    @Override
    public String toString() {
        return "PaymentResponse[paymentId=" + paymentId + ", orderId=" + orderId + ", status=" + status
                + ", clientSecret=" + (clientSecret == null ? "none" : "<redacted>") + "]";
    }
}
