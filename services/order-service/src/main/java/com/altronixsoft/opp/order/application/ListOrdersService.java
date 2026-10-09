package com.altronixsoft.opp.order.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Use case: list orders, newest first. A customer gets their own; an administrator gets all of them. */
@Service
public class ListOrdersService {

    public static final int MAX_PAGE_SIZE = 100;

    private final OrderRepository orders;

    public ListOrdersService(OrderRepository orders) {
        this.orders = orders;
    }

    /**
     * @param page zero-based page number
     * @param size 1..{@value #MAX_PAGE_SIZE}
     */
    @Transactional(readOnly = true)
    public OrderPage list(Caller caller, int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        return caller.admin() ? orders.findPage(page, size) : orders.findPageByCustomer(caller.subject(), page, size);
    }
}
