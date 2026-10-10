package com.altronixsoft.opp.payment.application;

import com.altronixsoft.opp.payment.domain.Payment;
import com.altronixsoft.opp.payment.domain.PaymentDomainEvent;
import com.altronixsoft.opp.payment.domain.PaymentStatus;
import com.altronixsoft.opp.payment.domain.PaymentStatusSource;
import com.altronixsoft.opp.payment.domain.Refund;
import com.altronixsoft.opp.payment.domain.RefundStatus;
import com.altronixsoft.opp.payment.domain.StripeOutcome;
import com.altronixsoft.opp.payment.domain.StripeWebhookEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Applies one webhook notification to the payment or refund it is about, inside the caller's transaction: the aggregate,
 * its history and its outbox events commit together with the event's new status (architecture §6.6).
 *
 * <p>Payments are looked up by PaymentIntent id, then by {@code metadata.paymentId}; refunds by Stripe refund id, then by
 * {@code metadata.refundId}. Nothing found is {@link NotificationOutcome#IGNORED} with a warning: the object is not one
 * this platform created. Statuses go through the ordering rule of §8.3 ({@code event.created} against
 * {@code last_stripe_event_at}); a stale report is {@link NotificationOutcome#STALE}.
 *
 * <p>A situation that time will resolve — the PaymentIntent or the Stripe refund id is not recorded yet because the
 * worker that created it has not committed — throws, so that the event is retried with backoff.
 */
public class StripeNotificationHandler {

    private static final Logger log = LoggerFactory.getLogger(StripeNotificationHandler.class);
    private static final String PAYMENT_FAILED = "payment_intent.payment_failed";

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final PaymentEventPublisher events;
    private final Clock clock;

    public StripeNotificationHandler(
            PaymentRepository payments, RefundRepository refunds, PaymentEventPublisher events, Clock clock) {
        this.payments = payments;
        this.refunds = refunds;
        this.events = events;
        this.clock = clock;
    }

    public NotificationOutcome handle(StripeNotification notification, StripeWebhookEvent event) {
        return switch (notification) {
            case StripeNotification.PaymentIntentChanged changed -> paymentIntentChanged(changed, event);
            case StripeNotification.ChargeRefunded refunded -> chargeRefunded(refunded, event);
            case StripeNotification.RefundFailed failed -> refundFailed(failed, event);
            case StripeNotification.DisputeCreated dispute -> disputeCreated(dispute, event);
            case StripeNotification.Unsupported unsupported -> {
                log.debug("Webhook {} of type {} is not handled", event.eventId(), unsupported.type());
                yield NotificationOutcome.IGNORED;
            }
        };
    }

    // ------------------------------------------------------------------------------------------ PaymentIntent

    private NotificationOutcome paymentIntentChanged(
            StripeNotification.PaymentIntentChanged changed, StripeWebhookEvent event) {
        Optional<Payment> found = payments.findByStripePaymentIntentId(changed.paymentIntentId())
                .or(() -> uuid(changed.paymentIdMetadata()).flatMap(payments::findById));
        if (found.isEmpty()) {
            return unknown(event, "PaymentIntent " + changed.paymentIntentId());
        }
        Payment payment = inContext(found.get());
        if (payment.stripePaymentIntentId() == null) {
            // Found by metadata: the initiation worker has not committed the PaymentIntent yet. Retry later.
            throw new IllegalStateException("Payment " + payment.id() + " has no PaymentIntent yet; webhook "
                    + event.eventId() + " for " + changed.paymentIntentId() + " waits for it");
        }
        if (!payment.stripePaymentIntentId().equals(changed.paymentIntentId())) {
            log.warn(
                    "Webhook {} is about PaymentIntent {}, but payment {} uses {}; ignored (an orphaned intent)",
                    event.eventId(),
                    changed.paymentIntentId(),
                    payment.id(),
                    payment.stripePaymentIntentId());
            return NotificationOutcome.IGNORED;
        }
        StripeOutcome outcome;
        if (PAYMENT_FAILED.equals(event.type())) {
            StripeNotification.PaymentError error = changed.lastPaymentError();
            outcome = payment.recordPaymentFailure(
                    changed.status(),
                    error == null ? null : error.code(),
                    error == null ? null : error.declineCode(),
                    error == null ? null : error.message(),
                    event.stripeCreatedAt(),
                    PaymentStatusSource.WEBHOOK,
                    event.eventId());
        } else {
            outcome = payment.applyStripeStatus(
                    changed.status(),
                    event.stripeCreatedAt(),
                    PaymentStatusSource.WEBHOOK,
                    event.eventId(),
                    changed.cancellationReason());
        }
        return save(payment, outcome, payment.correlationId(), payment.causedByEventId());
    }

    // ------------------------------------------------------------------------------------------ refunds

    private NotificationOutcome chargeRefunded(StripeNotification.ChargeRefunded refunded, StripeWebhookEvent event) {
        Optional<Payment> found = payments.findByStripePaymentIntentId(refunded.paymentIntentId());
        if (found.isEmpty()) {
            return unknown(event, "PaymentIntent " + refunded.paymentIntentId());
        }
        Payment payment = inContext(found.get());
        if (!refunded.fullyRefunded()) {
            log.warn(
                    "Webhook {}: the charge of payment {} was refunded partially; partial refunds are out of scope",
                    event.eventId(),
                    payment.id());
            return NotificationOutcome.IGNORED;
        }
        List<Refund> open = refunds.findByPaymentId(payment.id()).stream()
                .filter(refund -> !refund.status().isTerminal())
                .toList();
        if (open.isEmpty()) {
            if (payment.status() == PaymentStatus.REFUNDED) {
                return NotificationOutcome.STALE;
            }
            log.warn(
                    "Webhook {}: payment {} was refunded at Stripe, but the platform requested no refund; ignored",
                    event.eventId(),
                    payment.id());
            return NotificationOutcome.IGNORED;
        }
        Refund refund = open.getFirst();
        if (refund.status() == RefundStatus.REQUESTED) {
            throw new IllegalStateException("Refund " + refund.id() + " is not recorded as created at Stripe yet; "
                    + "webhook " + event.eventId() + " waits for the refund worker");
        }
        StripeOutcome outcome = payment.markRefunded(
                refund.refundRequestId(),
                refund.stripeRefundId(),
                event.stripeCreatedAt(),
                PaymentStatusSource.WEBHOOK,
                event.eventId());
        if (outcome == StripeOutcome.APPLIED) {
            refund.markSucceeded(now());
            refunds.save(refund);
        }
        return save(payment, outcome, refund.correlationId(), refund.causedByEventId());
    }

    private NotificationOutcome refundFailed(StripeNotification.RefundFailed failed, StripeWebhookEvent event) {
        Optional<Refund> found = refunds.findByStripeRefundId(failed.stripeRefundId())
                .or(() -> uuid(failed.refundIdMetadata()).flatMap(refunds::findById));
        if (found.isEmpty()) {
            return unknown(event, "refund " + failed.stripeRefundId());
        }
        Refund refund = found.get();
        switch (refund.status()) {
            case FAILED -> {
                return NotificationOutcome.STALE;
            }
            case SUCCEEDED -> {
                log.error(
                        "Webhook {}: refund {} failed after it had succeeded; the payment stays REFUNDED. "
                                + "Check it in the Stripe Dashboard.",
                        event.eventId(),
                        refund.id());
                return NotificationOutcome.IGNORED;
            }
            case REQUESTED ->
                throw new IllegalStateException("Refund " + refund.id() + " is not recorded as created at Stripe "
                        + "yet; webhook " + event.eventId() + " waits for the refund worker");
            case PENDING -> {
                // handled below
            }
        }
        Instant now = now();
        refund.markFailed(failed.failureReason(), now);
        refunds.save(refund);
        Payment payment = inContext(payments.findById(refund.paymentId())
                .orElseThrow(() -> new IllegalStateException("Refund " + refund.id() + " has no payment")));
        payment.recordRefundFailure(refund.refundRequestId(), failed.failureReason(), now);
        return save(payment, StripeOutcome.APPLIED, refund.correlationId(), refund.causedByEventId());
    }

    // ------------------------------------------------------------------------------------------ disputes

    private NotificationOutcome disputeCreated(StripeNotification.DisputeCreated dispute, StripeWebhookEvent event) {
        Optional<Payment> found = payments.findByStripePaymentIntentId(dispute.paymentIntentId());
        if (found.isEmpty()) {
            return unknown(event, "PaymentIntent " + dispute.paymentIntentId());
        }
        Payment payment = inContext(found.get());
        boolean changed = payment.markDisputed(dispute.disputeId(), dispute.reason(), event.stripeCreatedAt());
        log.warn("Payment {} is disputed ({}, reason {})", payment.id(), dispute.disputeId(), dispute.reason());
        return save(
                payment,
                changed ? StripeOutcome.APPLIED : StripeOutcome.UNCHANGED,
                payment.correlationId(),
                payment.causedByEventId());
    }

    // ------------------------------------------------------------------------------------------ helpers

    /** Puts the payment into the logging context (architecture §13); the processor clears it after each event. */
    private static Payment inContext(Payment payment) {
        MDC.put("paymentId", payment.id().toString());
        MDC.put("orderId", payment.orderId().toString());
        if (payment.correlationId() != null) {
            MDC.put("correlationId", payment.correlationId().toString());
        }
        return payment;
    }

    static void clearLoggingContext() {
        MDC.remove("paymentId");
        MDC.remove("orderId");
        MDC.remove("correlationId");
    }

    /**
     * Saves the payment and hands its events to the outbox, unless the report was stale. A report that changed no status
     * may still be news (a failed attempt on a PaymentIntent that stays {@code requires_payment_method}): what counts is
     * whether it registered an event.
     */
    private NotificationOutcome save(Payment payment, StripeOutcome outcome, UUID correlationId, UUID causationId) {
        if (outcome == StripeOutcome.STALE_IGNORED) {
            return NotificationOutcome.STALE;
        }
        payments.save(payment);
        List<PaymentDomainEvent> registered = payment.pullDomainEvents();
        events.publish(registered, correlationId, causationId);
        return outcome == StripeOutcome.APPLIED || !registered.isEmpty()
                ? NotificationOutcome.APPLIED
                : NotificationOutcome.STALE;
    }

    private static NotificationOutcome unknown(StripeWebhookEvent event, String what) {
        log.warn(
                "Webhook {} ({}) is about {}, which this platform does not know; ignored",
                event.eventId(),
                event.type(),
                what);
        return NotificationOutcome.IGNORED;
    }

    private static Optional<UUID> uuid(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
