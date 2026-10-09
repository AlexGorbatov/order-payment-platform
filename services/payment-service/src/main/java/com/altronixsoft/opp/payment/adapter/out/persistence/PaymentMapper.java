package com.altronixsoft.opp.payment.adapter.out.persistence;

import com.altronixsoft.opp.payment.domain.Money;
import com.altronixsoft.opp.payment.domain.Payment;
import com.altronixsoft.opp.payment.domain.PaymentStatusChange;
import com.altronixsoft.opp.payment.domain.Refund;
import com.altronixsoft.opp.payment.domain.StripeWebhookEvent;
import java.util.List;

/**
 * Explicit mapping between the domain and the JPA entities, in both directions. No reflection-based mapper: every field
 * is visible here, so a change of the domain or the schema cannot slip through silently.
 */
final class PaymentMapper {

    private PaymentMapper() {}

    // ------------------------------------------------------------------------------------------ payment

    static Payment toDomain(PaymentEntity entity) {
        List<PaymentStatusChange> history = entity.history.stream()
                .map(row -> new PaymentStatusChange(
                        row.fromStatus, row.toStatus, row.source, row.stripeEventId, row.occurredAt))
                .toList();
        return Payment.restore(
                entity.id,
                entity.orderId,
                entity.customerId,
                Money.of(entity.amountMinor, entity.currency),
                entity.status,
                entity.stripePaymentIntentId,
                entity.lastStripeEventAt,
                entity.lastErrorCode,
                entity.lastErrorMessage,
                entity.cancelRequested,
                entity.cancelSentAt,
                entity.disputed,
                entity.attempts,
                entity.nextAttemptAt,
                entity.createdAt,
                entity.updatedAt,
                entity.version,
                history);
    }

    /** A new row for a payment that has never been stored. */
    static PaymentEntity toNewEntity(Payment payment) {
        PaymentEntity entity = new PaymentEntity();
        entity.id = payment.id();
        entity.orderId = payment.orderId();
        entity.customerId = payment.customerId();
        entity.amountMinor = payment.amount().amountMinor();
        entity.currency = payment.amount().currencyCode();
        entity.createdAt = payment.createdAt();
        applyChanges(payment, entity);
        return entity;
    }

    /**
     * Copies what can change after creation onto a loaded entity and appends the history entries it does not have yet.
     * Order, customer and amount never change; the history is append-only.
     */
    static void applyChanges(Payment payment, PaymentEntity entity) {
        entity.status = payment.status();
        entity.stripePaymentIntentId = payment.stripePaymentIntentId();
        entity.lastStripeEventAt = payment.lastStripeEventAt();
        entity.lastErrorCode = payment.lastErrorCode();
        entity.lastErrorMessage = payment.lastErrorMessage();
        entity.cancelRequested = payment.cancelRequested();
        entity.cancelSentAt = payment.cancelSentAt();
        entity.disputed = payment.disputed();
        entity.attempts = payment.attempts();
        entity.nextAttemptAt = payment.nextAttemptAt();
        entity.updatedAt = payment.updatedAt();
        List<PaymentStatusChange> history = payment.history();
        for (PaymentStatusChange change : history.subList(entity.history.size(), history.size())) {
            PaymentStatusHistoryEntity row = new PaymentStatusHistoryEntity();
            row.payment = entity;
            row.fromStatus = change.from();
            row.toStatus = change.to();
            row.source = change.source();
            row.stripeEventId = change.stripeEventId();
            row.occurredAt = change.occurredAt();
            entity.history.add(row);
        }
    }

    // ------------------------------------------------------------------------------------------ refund

    static Refund toDomain(RefundEntity entity) {
        return Refund.restore(
                entity.id,
                entity.paymentId,
                entity.refundRequestId,
                Money.of(entity.amountMinor, entity.currency),
                entity.reason,
                entity.status,
                entity.stripeRefundId,
                entity.failureReason,
                entity.attempts,
                entity.nextAttemptAt,
                entity.createdAt,
                entity.updatedAt,
                entity.version);
    }

    static RefundEntity toNewEntity(Refund refund) {
        RefundEntity entity = new RefundEntity();
        entity.id = refund.id();
        entity.paymentId = refund.paymentId();
        entity.refundRequestId = refund.refundRequestId();
        entity.amountMinor = refund.amount().amountMinor();
        entity.currency = refund.amount().currencyCode();
        entity.reason = refund.reason();
        entity.createdAt = refund.createdAt();
        applyChanges(refund, entity);
        return entity;
    }

    static void applyChanges(Refund refund, RefundEntity entity) {
        entity.status = refund.status();
        entity.stripeRefundId = refund.stripeRefundId();
        entity.failureReason = refund.failureReason();
        entity.attempts = refund.attempts();
        entity.nextAttemptAt = refund.nextAttemptAt();
        entity.updatedAt = refund.updatedAt();
    }

    // ------------------------------------------------------------------------------------------ webhook event

    static StripeWebhookEvent toDomain(StripeWebhookEventEntity entity) {
        return StripeWebhookEvent.restore(
                entity.eventId,
                entity.type,
                entity.apiVersion,
                entity.livemode,
                entity.stripeCreatedAt,
                entity.payload,
                entity.status,
                entity.attempts,
                entity.nextAttemptAt,
                entity.lastError,
                entity.receivedAt,
                entity.processedAt);
    }

    /** The processing state; the received event itself (type, payload, ...) never changes. */
    static void applyChanges(StripeWebhookEvent event, StripeWebhookEventEntity entity) {
        entity.status = event.status();
        entity.attempts = event.attempts();
        entity.nextAttemptAt = event.nextAttemptAt();
        entity.lastError = event.lastError();
        entity.processedAt = event.processedAt();
    }
}
