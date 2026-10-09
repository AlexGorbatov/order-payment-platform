package com.altronixsoft.opp.order.adapter.out.persistence;

import com.altronixsoft.opp.order.application.OrderConcurrentlyModifiedException;
import com.altronixsoft.opp.order.application.OrderPage;
import com.altronixsoft.opp.order.application.OrderRepository;
import com.altronixsoft.opp.order.domain.Order;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
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
    @Transactional(readOnly = true)
    public OrderPage findPage(int page, int size) {
        return toPage(orders.findAll(pageRequest(page, size)), page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderPage findPageByCustomer(String customerId, int page, int size) {
        return toPage(orders.findByCustomerId(customerId, pageRequest(page, size)), page, size);
    }

    /** Newest first; the id breaks ties so that paging through orders created in the same instant is stable. */
    private static PageRequest pageRequest(int page, int size) {
        return PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }

    private static OrderPage toPage(Page<OrderEntity> result, int page, int size) {
        return new OrderPage(
                result.getContent().stream().map(OrderMapper::toDomain).toList(),
                page,
                size,
                result.getTotalElements());
    }

    /** The locks only mean something inside the caller's transaction, so one is required. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Order> lockOverduePendingPayment(Instant placedBefore, int limit) {
        List<UUID> ids = orders.lockOverduePendingPaymentIds(placedBefore, limit);
        return orders.findAllById(ids).stream()
                .map(OrderMapper::toDomain)
                .sorted(Comparator.comparing(Order::createdAt).thenComparing(Order::id))
                .toList();
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
