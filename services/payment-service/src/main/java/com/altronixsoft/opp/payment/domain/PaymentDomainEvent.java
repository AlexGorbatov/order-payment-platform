package com.altronixsoft.opp.payment.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * What happened to a {@link Payment}, registered by the aggregate and handed to the outbox by the application layer in the
 * transaction that saves it (architecture §7.1). The adapter turns them into the integration events of §9.3.
 */
public sealed interface PaymentDomainEvent {

    UUID paymentId();

    UUID orderId();

    /** When it happened. */
    Instant occurredAt();

    /** The PaymentIntent was created at the provider. */
    record Initiated(UUID paymentId, UUID orderId, String stripePaymentIntentId, Instant occurredAt)
            implements PaymentDomainEvent {}

    /** The PaymentIntent could not be created: a permanent error, or the idempotency window elapsed. */
    record InitiationFailed(UUID paymentId, UUID orderId, String errorCode, Instant occurredAt)
            implements PaymentDomainEvent {}

    /** The customer must complete an extra step (3-D Secure). */
    record ActionRequired(UUID paymentId, UUID orderId, Instant occurredAt) implements PaymentDomainEvent {}

    /** An attempt failed; the customer may retry. {@code declineCode} may be {@code null}. */
    record AttemptFailed(UUID paymentId, UUID orderId, String errorCode, String declineCode, Instant occurredAt)
            implements PaymentDomainEvent {}

    /** The money was captured. */
    record Succeeded(UUID paymentId, UUID orderId, Money amount, String stripePaymentIntentId, Instant occurredAt)
            implements PaymentDomainEvent {}

    /** The PaymentIntent was cancelled at Stripe. */
    record Canceled(UUID paymentId, UUID orderId, String reason, Instant occurredAt) implements PaymentDomainEvent {}

    /** The full refund succeeded. */
    record Refunded(
            UUID paymentId, UUID orderId, UUID refundRequestId, String stripeRefundId, Money amount, Instant occurredAt)
            implements PaymentDomainEvent {}

    /** A refund failed; the payment is unchanged. */
    record RefundFailed(UUID paymentId, UUID orderId, UUID refundRequestId, String failureReason, Instant occurredAt)
            implements PaymentDomainEvent {}

    /** The customer disputed the charge. */
    record Disputed(UUID paymentId, UUID orderId, String disputeId, String reason, Instant occurredAt)
            implements PaymentDomainEvent {}
}
