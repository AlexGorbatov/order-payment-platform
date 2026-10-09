package com.altronixsoft.opp.order.application;

import java.util.Objects;
import java.util.UUID;

/**
 * A consumed payment event, translated from the wire contract by the Kafka adapter.
 *
 * @param eventId id of the consumed event; recorded in the status history and the causation of any event published in
 *     response
 * @param orderId the order the payment belongs to
 * @param correlationId the business flow; events published in response carry it on
 * @param outcome what happened to the payment
 */
public record PaymentEventCommand(UUID eventId, UUID orderId, UUID correlationId, PaymentOutcome outcome) {

    public PaymentEventCommand {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(outcome, "outcome");
    }

    /** What payment-service reports (architecture §9.3, the payment events order-service reacts to). */
    public sealed interface PaymentOutcome {

        /** {@code PaymentSucceeded}. */
        record Succeeded() implements PaymentOutcome {}

        /** {@code PaymentInitiationFailed}. */
        record InitiationFailed(String errorCode) implements PaymentOutcome {}

        /** {@code PaymentCanceled}. */
        record Canceled(String reason) implements PaymentOutcome {}

        /** {@code PaymentAttemptFailed}; {@code declineCode} may be {@code null}. */
        record AttemptFailed(String errorCode, String declineCode) implements PaymentOutcome {}

        /** {@code PaymentActionRequired}. */
        record ActionRequired() implements PaymentOutcome {}

        /** {@code PaymentRefunded}. */
        record Refunded(UUID refundRequestId) implements PaymentOutcome {

            public Refunded {
                Objects.requireNonNull(refundRequestId, "refundRequestId");
            }
        }

        /** {@code PaymentRefundFailed}. */
        record RefundFailed(UUID refundRequestId, String failureReason) implements PaymentOutcome {

            public RefundFailed {
                Objects.requireNonNull(refundRequestId, "refundRequestId");
            }
        }

        /** {@code PaymentDisputed}. */
        record Disputed(String disputeId) implements PaymentOutcome {}
    }
}
