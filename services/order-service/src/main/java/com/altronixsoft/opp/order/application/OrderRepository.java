package com.altronixsoft.opp.order.application;

import com.altronixsoft.opp.order.domain.Order;
import java.util.Optional;
import java.util.UUID;

/** Port: storage of order aggregates. */
public interface OrderRepository {

    Optional<Order> findById(UUID id);

    /**
     * Stores a new order or the changes of a loaded one, including its new status-history entries.
     *
     * @return the stored order with its current {@link Order#version() version}; use it, not the argument, for further
     *     changes. The argument keeps the domain events it registered, so collect them from it.
     * @throws OrderConcurrentlyModifiedException the order was changed by someone else since it was loaded
     */
    Order save(Order order);
}
