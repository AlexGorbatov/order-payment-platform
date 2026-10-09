package com.altronixsoft.opp.payment.application;

/**
 * What a Stripe webhook tells the platform, reduced to the fields it acts on (architecture §8.3). Built by the
 * {@link WebhookPayloadParser} from {@code data.object}; strings are sanitized.
 */
public sealed interface StripeNotification {

    /**
     * {@code payment_intent.processing | requires_action | payment_failed | succeeded | canceled}.
     *
     * @param paymentIntentId {@code data.object.id}
     * @param paymentIdMetadata {@code metadata.paymentId}, the fallback lookup; may be {@code null}
     * @param status {@code data.object.status} at the time of the event
     * @param cancellationReason for {@code canceled}; may be {@code null}
     * @param lastPaymentError for {@code payment_failed}; may be {@code null}
     */
    record PaymentIntentChanged(
            String paymentIntentId,
            String paymentIdMetadata,
            String status,
            String cancellationReason,
            PaymentError lastPaymentError)
            implements StripeNotification {}

    /** {@code last_payment_error}: {@code code}, {@code decline_code} and the customer-facing {@code message}. */
    record PaymentError(String code, String declineCode, String message) {}

    /**
     * {@code charge.refunded} (the object is the charge).
     *
     * @param fullyRefunded {@code data.object.refunded}: the whole amount went back (partial refunds are out of scope)
     */
    record ChargeRefunded(String paymentIntentId, boolean fullyRefunded) implements StripeNotification {}

    /**
     * {@code refund.failed} (the object is the refund).
     *
     * @param refundIdMetadata {@code metadata.refundId}, the fallback lookup; may be {@code null}
     */
    record RefundFailed(String stripeRefundId, String paymentIntentId, String refundIdMetadata, String failureReason)
            implements StripeNotification {}

    /** {@code charge.dispute.created} (the object is the dispute). */
    record DisputeCreated(String disputeId, String paymentIntentId, String reason) implements StripeNotification {}

    /** A type the platform does not handle. */
    record Unsupported(String type) implements StripeNotification {}
}
