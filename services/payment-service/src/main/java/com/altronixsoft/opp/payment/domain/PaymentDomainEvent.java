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
}
