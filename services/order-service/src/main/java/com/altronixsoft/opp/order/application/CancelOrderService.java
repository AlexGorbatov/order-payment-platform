package com.altronixsoft.opp.order.application;

import com.altronixsoft.opp.order.domain.CancelReason;
import com.altronixsoft.opp.order.domain.Order;
import com.altronixsoft.opp.order.domain.Trigger;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use case: the customer cancels their own order. Only an order that is still {@code PENDING_PAYMENT} can be cancelled
 * (architecture §5.1); anything else is an {@link com.altronixsoft.opp.order.domain.IllegalOrderTransitionException}.
 *
 * <p>Like placing an order, the domain event {@code OrderCancelled} stays on the aggregate until the outbox (T09)
 * takes it in this transaction.
 */
@Service
public class CancelOrderService {

    private final OrderRepository orders;
    private final Clock clock;

    public CancelOrderService(OrderRepository orders, Clock clock) {
        this.orders = orders;
        this.clock = clock;
    }

    /**
     * @return the stored order
     * @throws OrderNotFoundException there is no such order, or it belongs to someone else
     * @throws com.altronixsoft.opp.order.domain.IllegalOrderTransitionException the order is not pending payment
     * @throws OrderConcurrentlyModifiedException the order changed while it was being cancelled
     */
    @Transactional
    public Order cancel(UUID id, Caller caller) {
        Order order = orders.findById(id).filter(caller::owns).orElseThrow(() -> new OrderNotFoundException(id));
        order.cancel(CancelReason.CUSTOMER, Trigger.api(clock.instant().truncatedTo(ChronoUnit.MICROS)));
        return orders.save(order);
    }
}
