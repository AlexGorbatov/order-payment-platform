package com.altronixsoft.opp.order.application;

import com.altronixsoft.opp.order.domain.CancelReason;
import com.altronixsoft.opp.order.domain.Order;
import com.altronixsoft.opp.order.domain.Trigger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use case: cancel orders whose payment did not arrive in time ({@code CANCELLED(TIMEOUT)}, architecture §6.4). The
 * {@code OrderCancelled} event makes payment-service cancel the PaymentIntent; if the payment succeeded meanwhile,
 * {@link ApplyPaymentEventService} compensates with a refund (F18).
 */
@Service
public class ExpireUnpaidOrdersService {

    private final OrderRepository orders;
    private final OrderEventPublisher events;
    private final Clock clock;

    public ExpireUnpaidOrdersService(OrderRepository orders, OrderEventPublisher events, Clock clock) {
        this.orders = orders;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Cancels at most {@code batchSize} of the oldest orders that have been awaiting payment for longer than
     * {@code paymentTimeout}, in one transaction. The orders are claimed with {@code FOR UPDATE SKIP LOCKED}, so several
     * instances can run this at the same time without cancelling an order twice.
     *
     * @param correlationId the flow of this job run; every published {@code OrderCancelled} carries it
     * @return how many orders were cancelled; fewer than {@code batchSize} means none is overdue any more
     */
    @Transactional
    public int expireBatch(Duration paymentTimeout, int batchSize, UUID correlationId) {
        Objects.requireNonNull(paymentTimeout, "paymentTimeout");
        Objects.requireNonNull(correlationId, "correlationId");
        if (paymentTimeout.isNegative() || paymentTimeout.isZero()) {
            throw new IllegalArgumentException("paymentTimeout must be positive, got " + paymentTimeout);
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive, got " + batchSize);
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<Order> overdue = orders.lockOverduePendingPayment(now.minus(paymentTimeout), batchSize);
        for (Order order : overdue) {
            order.cancel(CancelReason.TIMEOUT, Trigger.job(now));
            orders.save(order);
            events.publish(order.pullDomainEvents(), correlationId);
        }
        return overdue.size();
    }
}
