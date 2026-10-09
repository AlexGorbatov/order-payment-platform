package com.altronixsoft.opp.order.application;

import com.altronixsoft.opp.order.application.PaymentEventCommand.PaymentOutcome;
import com.altronixsoft.opp.order.domain.CancelReason;
import com.altronixsoft.opp.order.domain.Order;
import com.altronixsoft.opp.order.domain.OrderStatus;
import com.altronixsoft.opp.order.domain.RefundReason;
import com.altronixsoft.opp.order.domain.Trigger;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use case: order-service's side of the saga (architecture §6, ADR-0002). Applies one payment event to its order.
 *
 * <p>Events arrive at least once and, because retry topics do not keep the order of one key (ADR-0007), not always in
 * order. So every rule first asks whether the order is still in the status the event expects:
 *
 * <ul>
 *   <li>yes — the change is applied and its integration events are published in this transaction;
 *   <li>no, and a duplicate or a race between the services explains it — {@link PaymentEventResult#IGNORED};
 *   <li>no, and nothing explains it — {@link UnexpectedPaymentEventException}, which the adapter dead-letters.
 * </ul>
 *
 * The caller runs this inside the inbox transaction, so a redelivered event never reaches it twice.
 */
@Service
public class ApplyPaymentEventService {

    private static final Logger log = LoggerFactory.getLogger(ApplyPaymentEventService.class);

    private final OrderRepository orders;
    private final OrderEventPublisher events;
    private final IdGenerator ids;
    private final Clock clock;

    public ApplyPaymentEventService(OrderRepository orders, OrderEventPublisher events, IdGenerator ids, Clock clock) {
        this.orders = orders;
        this.events = events;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * @throws UnexpectedPaymentEventException the event cannot be explained by the order's state
     * @throws OrderConcurrentlyModifiedException the order changed concurrently; retrying will see the new state
     */
    @Transactional
    public PaymentEventResult apply(PaymentEventCommand command) {
        Order order = orders.findById(command.orderId())
                .orElseThrow(() -> new UnexpectedPaymentEventException(
                        command.orderId(), "Payment event " + command.eventId() + " refers to an unknown order"));
        Trigger trigger = Trigger.event(command.eventId(), clock.instant().truncatedTo(ChronoUnit.MICROS));

        PaymentEventResult result = switch (command.outcome()) {
            case PaymentOutcome.Succeeded ignored -> succeeded(order, trigger);
            case PaymentOutcome.InitiationFailed ignored ->
                cancelIfPending(order, CancelReason.PAYMENT_INITIATION_FAILED, trigger);
            case PaymentOutcome.Canceled ignored -> cancelIfPending(order, CancelReason.PAYMENT_CANCELED, trigger);
            case PaymentOutcome.AttemptFailed failed -> {
                if (order.status() != OrderStatus.PENDING_PAYMENT) {
                    yield PaymentEventResult.IGNORED;
                }
                // Not a cancellation: Stripe lets the customer retry on the same PaymentIntent with another
                // payment method, so the order keeps waiting for payment until the payment timeout (§6.2).
                order.notePaymentAttemptFailed(failed.errorCode(), failed.declineCode(), trigger);
                yield PaymentEventResult.APPLIED;
            }
            case PaymentOutcome.ActionRequired ignored -> {
                if (order.status() != OrderStatus.PENDING_PAYMENT) {
                    yield PaymentEventResult.IGNORED;
                }
                // The customer completes 3-D Secure; success or failure follows as another event (§6.3).
                order.notePaymentActionRequired(trigger);
                yield PaymentEventResult.APPLIED;
            }
            case PaymentOutcome.Refunded refunded -> refunded(order, refunded.refundRequestId(), trigger);
            case PaymentOutcome.RefundFailed failed ->
                refundFailed(order, failed.refundRequestId(), failed.failureReason(), trigger);
            case PaymentOutcome.Disputed ignored -> {
                if (order.disputed()) {
                    yield PaymentEventResult.IGNORED;
                }
                order.markDisputed(trigger);
                yield PaymentEventResult.APPLIED;
            }
        };

        if (result == PaymentEventResult.IGNORED) {
            log.info(
                    "Ignoring {} for order {} in status {} (event {})",
                    command.outcome().getClass().getSimpleName(),
                    order.id(),
                    order.status(),
                    command.eventId());
            return result;
        }
        orders.save(order);
        events.publish(order.pullDomainEvents(), command.correlationId());
        return result;
    }

    private PaymentEventResult succeeded(Order order, Trigger trigger) {
        return switch (order.status()) {
            case PENDING_PAYMENT -> {
                order.markPaid(trigger);
                yield PaymentEventResult.APPLIED;
            }
            case CANCELLED -> {
                // F18: the customer (or the timeout) cancelled while the payment was being confirmed, and Stripe
                // charged anyway. The money goes back automatically.
                order.requestRefund(RefundReason.LATE_PAYMENT_AFTER_CANCEL, ids.newId(), trigger);
                yield PaymentEventResult.COMPENSATED;
            }
            // The success has already been applied; this is a late duplicate of it.
            case PAID, REFUND_REQUESTED, REFUNDED, REFUND_FAILED -> PaymentEventResult.IGNORED;
        };
    }

    /**
     * The payment will not happen. An order still waiting for it is cancelled; in any other status the order has
     * already moved on (typically the customer cancelled first and this is the payment's echo, §6.4).
     */
    private static PaymentEventResult cancelIfPending(Order order, CancelReason reason, Trigger trigger) {
        if (order.status() != OrderStatus.PENDING_PAYMENT) {
            return PaymentEventResult.IGNORED;
        }
        order.cancel(reason, trigger);
        return PaymentEventResult.APPLIED;
    }

    private static PaymentEventResult refunded(Order order, UUID refundRequestId, Trigger trigger) {
        if (isOutcomeOfAnOlderRequest(order, refundRequestId)) {
            return PaymentEventResult.IGNORED;
        }
        return switch (order.status()) {
            case REFUND_REQUESTED -> {
                order.markRefunded(trigger);
                yield PaymentEventResult.APPLIED;
            }
            case REFUNDED -> PaymentEventResult.IGNORED;
            default -> throw contradiction(order, "PaymentRefunded", refundRequestId);
        };
    }

    private static PaymentEventResult refundFailed(
            Order order, UUID refundRequestId, String failureReason, Trigger trigger) {
        if (isOutcomeOfAnOlderRequest(order, refundRequestId)) {
            return PaymentEventResult.IGNORED;
        }
        return switch (order.status()) {
            case REFUND_REQUESTED -> {
                order.markRefundFailed(failureReason, trigger);
                yield PaymentEventResult.APPLIED;
            }
            case REFUND_FAILED -> PaymentEventResult.IGNORED;
            default -> throw contradiction(order, "PaymentRefundFailed", refundRequestId);
        };
    }

    /**
     * An administrator may retry a failed refund with a new request id before the outcome of the old request has
     * arrived; that outcome is stale. An order that never requested a refund at all cannot have a refund outcome.
     */
    private static boolean isOutcomeOfAnOlderRequest(Order order, UUID refundRequestId) {
        if (order.refundRequestId() == null) {
            throw new UnexpectedPaymentEventException(
                    order.id(),
                    "Refund outcome for request " + refundRequestId + " but order " + order.id()
                            + " never requested a refund");
        }
        return !order.refundRequestId().equals(refundRequestId);
    }

    private static UnexpectedPaymentEventException contradiction(Order order, String event, UUID refundRequestId) {
        return new UnexpectedPaymentEventException(
                order.id(),
                event + " for refund request " + refundRequestId + " contradicts order " + order.id() + " in status "
                        + order.status());
    }
}
