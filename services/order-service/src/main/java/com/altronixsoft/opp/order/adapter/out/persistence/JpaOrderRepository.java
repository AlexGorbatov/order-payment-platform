package com.altronixsoft.opp.order.adapter.out.persistence;

import com.altronixsoft.opp.order.application.OrderConcurrentlyModifiedException;
import com.altronixsoft.opp.order.application.OrderRepository;
import com.altronixsoft.opp.order.domain.Order;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link OrderRepository} on JPA.
 *
 * <p>Optimistic locking is checked twice. The order carries the version it was loaded with; {@code save} loads the
 * current row and refuses to go on if the versions differ (a change that was committed before this call). The
 * {@code @Version} column then guards the flush itself: a change committed between that check and the flush makes the
 * {@code UPDATE ... WHERE version = ?} fail. Both surface as {@link OrderConcurrentlyModifiedException}.
 */
@Repository
@Transactional
class JpaOrderRepository implements OrderRepository {

    private final OrderJpaRepository orders;

    JpaOrderRepository(OrderJpaRepository orders) {
        this.orders = orders;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Order> findById(UUID id) {
        return orders.findById(id).map(OrderMapper::toDomain);
    }

    @Override
    public Order save(Order order) {
        try {
            OrderEntity entity;
            if (order.version() == null) {
                entity = OrderMapper.toNewEntity(order);
            } else {
                entity = orders.findById(order.id())
                        .orElseThrow(() -> new OrderConcurrentlyModifiedException(order.id()));
                if (!order.version().equals(entity.version)) {
                    throw new OrderConcurrentlyModifiedException(order.id());
                }
                OrderMapper.applyChanges(order, entity);
            }
            return OrderMapper.toDomain(orders.saveAndFlush(entity));
        } catch (OptimisticLockingFailureException e) {
            throw new OrderConcurrentlyModifiedException(order.id(), e);
        }
    }
}
