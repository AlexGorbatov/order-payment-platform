package com.altronixsoft.opp.order.application;

import com.altronixsoft.opp.order.domain.Order;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port: storage of order aggregates. */
public interface OrderRepository {

    Optional<Order> findById(UUID id);

    /** All orders, newest first ({@code createdAt} descending, then id descending), {@code page} counted from 0. */
    OrderPage findPage(int page, int size);

    /** The orders of one customer, newest first. */
    OrderPage findPageByCustomer(String customerId, int page, int size);

    /**
     * Stores a new order or the changes of a loaded one, including its new status-history entries.
     *
     * @return the stored order with its current {@link Order#version() version}; use it, not the argument, for further
     *     changes. The argument keeps the domain events it registered, so collect them from it.
     * @throws OrderConcurrentlyModifiedException the order was changed by someone else since it was loaded
     */
    Order save(Order order);

    /**
     * Claims up to {@code limit} orders that are {@code PENDING_PAYMENT} and were placed before {@code placedBefore},
     * oldest first, and locks them until the caller's transaction ends. Orders another transaction has locked are
     * skipped ({@code FOR UPDATE SKIP LOCKED}), so concurrent callers get disjoint batches.
     *
     * @throws org.springframework.transaction.IllegalTransactionStateException without an active transaction
     */
    List<Order> lockOverduePendingPayment(Instant placedBefore, int limit);
}
