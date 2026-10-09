package com.altronixsoft.opp.payment.application;

import com.altronixsoft.opp.payment.application.OrderEventCommand.Outcome;
import com.altronixsoft.opp.payment.domain.CancelRequest;
import com.altronixsoft.opp.payment.domain.Payment;
import com.altronixsoft.opp.payment.domain.PaymentStatus;
import com.altronixsoft.opp.payment.domain.Refund;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use case: payment-service's reaction to the order events it consumes (architecture §6). It only writes state: it never
 * calls Stripe (ADR-0008). The workers pick the work up from the database: the payment is created {@code CREATED} and due
 * at once for {@code PaymentInitiationWorker}; a cancellation sets {@code cancel_requested} for the cancellation worker; a
 * refund is created {@code REQUESTED} for the refund worker.
 *
 * <p>A payment still {@code CREATED} is cancelled on the spot and {@code PaymentCanceled} is published. A refund for a
 * payment that is not {@code SUCCEEDED} (nothing was paid, or it is already refunded) is recorded as a {@code FAILED}
 * refund and {@code PaymentRefundFailed} is published, so the order shows {@code REFUND_FAILED} instead of waiting.
 *
 * <p>The caller runs this inside the inbox transaction, so a redelivered event never reaches it twice. A <em>different</em>
 * event that repeats the same business fact (a replay from the dead-letter topic, a second event for the same order or
 * refund request) is recognised by the unique keys and is a no-op (F22).
 */
@Service
public class ApplyOrderEventService {

    private static final Logger log = LoggerFactory.getLogger(ApplyOrderEventService.class);

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final PaymentEventPublisher events;
    private final IdGenerator ids;
    private final Clock clock;

    public ApplyOrderEventService(
            PaymentRepository payments,
            RefundRepository refunds,
            PaymentEventPublisher events,
            IdGenerator ids,
            Clock clock) {
        this.payments = payments;
        this.refunds = refunds;
        this.events = events;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * @throws PaymentNotFoundException a cancellation or refund for an order without a payment: the {@code OrderCreated}
     *     may still be in flight on a retry topic, so the platform retries; if it never arrives, the event is dead-lettered
     * @throws UnexpectedOrderEventException a refund whose amount is not the payment's (only full refunds exist)
     * @throws PaymentConcurrentlyModifiedException the payment changed concurrently; retrying sees the new state
     */
    @Transactional
    public OrderEventResult apply(OrderEventCommand command) {
        return switch (command.outcome()) {
            case Outcome.Created created -> create(command, created);
            case Outcome.Cancelled cancelled -> cancel(command, cancelled);
            case Outcome.RefundRequested refund -> refund(command, refund);
        };
    }

    private OrderEventResult create(OrderEventCommand command, Outcome.Created created) {
        if (payments.findByOrderId(command.orderId()).isPresent()) {
            log.info("Order {} already has a payment; ignoring event {}", command.orderId(), command.eventId());
            return OrderEventResult.PAYMENT_ALREADY_EXISTS;
        }
        Payment payment = Payment.create(
                ids.newId(),
                command.orderId(),
                created.customerId(),
                created.amount(),
                clock.instant().truncatedTo(ChronoUnit.MICROS),
                command.correlationId(),
                command.eventId());
        payments.save(payment);
        return OrderEventResult.PAYMENT_CREATED;
    }

    private OrderEventResult cancel(OrderEventCommand command, Outcome.Cancelled cancelled) {
        Payment payment = payments.findByOrderId(command.orderId())
                .orElseThrow(() -> new PaymentNotFoundException(command.orderId()));
        CancelRequest request = payment.requestCancel(clock.instant().truncatedTo(ChronoUnit.MICROS));
        log.info(
                "Order {} cancelled ({}): payment {} is {} -> {}",
                command.orderId(),
                cancelled.reason(),
                payment.id(),
                payment.status(),
                request);
        return switch (request) {
            case CANCELED_LOCALLY -> {
                payments.save(payment);
                events.publish(payment.pullDomainEvents(), command.correlationId(), command.eventId());
                yield OrderEventResult.PAYMENT_CANCELED;
            }
            case CANCEL_SCHEDULED -> {
                payments.save(payment);
                yield OrderEventResult.CANCEL_SCHEDULED;
            }
            case ALREADY_REQUESTED, ALREADY_CANCELED -> OrderEventResult.CANCEL_ALREADY_PENDING;
            case NOT_CANCELABLE -> OrderEventResult.CANCEL_TOO_LATE;
        };
    }

    private OrderEventResult refund(OrderEventCommand command, Outcome.RefundRequested request) {
        Payment payment = payments.findByOrderId(command.orderId())
                .orElseThrow(() -> new PaymentNotFoundException(command.orderId()));
        if (refunds.findByRefundRequestId(request.refundRequestId()).isPresent()) {
            log.info(
                    "Refund request {} was handled before; ignoring event {}",
                    request.refundRequestId(),
                    command.eventId());
            return OrderEventResult.REFUND_ALREADY_REQUESTED;
        }
        if (!payment.amount().equals(request.amount())) {
            throw new UnexpectedOrderEventException(
                    command.orderId(),
                    "Refund " + request.refundRequestId() + " asks for " + request.amount() + " but payment "
                            + payment.id() + " is for " + payment.amount() + "; only full refunds exist");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Refund refund = Refund.request(
                ids.newId(),
                payment.id(),
                request.refundRequestId(),
                request.amount(),
                request.reason(),
                now,
                command.correlationId(),
                command.eventId());
        if (payment.status() != PaymentStatus.SUCCEEDED) {
            // Nothing to give back (never paid, or already refunded): the request fails, visibly, without Stripe.
            String reason = payment.status() == PaymentStatus.REFUNDED ? "already_refunded" : "payment_not_succeeded";
            log.warn(
                    "Refund request {} for payment {} in status {} fails: {}",
                    request.refundRequestId(),
                    payment.id(),
                    payment.status(),
                    reason);
            refund.markFailed(reason, now);
            refunds.save(refund);
            payment.recordRefundFailure(request.refundRequestId(), reason, now);
            payments.save(payment);
            events.publish(payment.pullDomainEvents(), command.correlationId(), command.eventId());
            return OrderEventResult.REFUND_FAILED;
        }
        refunds.save(refund);
        return OrderEventResult.REFUND_REQUESTED;
    }
}
