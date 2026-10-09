package com.altronixsoft.opp.order.application;

import com.altronixsoft.opp.order.domain.Order;
import com.altronixsoft.opp.order.domain.RefundReason;
import com.altronixsoft.opp.order.domain.Trigger;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use case: an administrator requests a full refund of a paid order, or retries one that failed. The order moves to
 * {@code REFUND_REQUESTED}; the money moves later, when payment-service has processed the request.
 *
 * <p>The refund request id is generated here and travels in the {@code RefundRequested} event, so every retry of a
 * failed refund is a new request with its own id (architecture §5.3). {@code OrderRefundRequested} goes to the outbox in
 * the same transaction.
 */
@Service
public class RefundOrderService {

    private final OrderRepository orders;
    private final OrderEventPublisher events;
    private final IdGenerator ids;
    private final Clock clock;

    public RefundOrderService(OrderRepository orders, OrderEventPublisher events, IdGenerator ids, Clock clock) {
        this.orders = orders;
        this.events = events;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * @param correlationId the business flow the refund belongs to; its event carries it
     * @return the stored order
     * @throws OperationNotPermittedException the caller is not an administrator
     * @throws OrderNotFoundException there is no such order
     * @throws com.altronixsoft.opp.order.domain.IllegalOrderTransitionException the order is neither PAID nor
     *     REFUND_FAILED
     * @throws OrderConcurrentlyModifiedException the order changed while the refund was being requested
     */
    @Transactional
    public Order refund(UUID id, Caller caller, UUID correlationId) {
        if (!caller.admin()) {
            throw new OperationNotPermittedException("Only an administrator can refund an order");
        }
        Order order = orders.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
        order.requestRefund(
                RefundReason.ADMIN, ids.newId(), Trigger.api(clock.instant().truncatedTo(ChronoUnit.MICROS)));
        Order stored = orders.save(order);
        events.publish(order.pullDomainEvents(), correlationId);
        return stored;
    }
}
