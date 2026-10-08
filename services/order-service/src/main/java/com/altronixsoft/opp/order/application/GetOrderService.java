package com.altronixsoft.opp.order.application;

import com.altronixsoft.opp.order.domain.Order;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Use case: read one order. A customer sees only their own; an administrator sees every order. */
@Service
public class GetOrderService {

    private final OrderRepository orders;

    public GetOrderService(OrderRepository orders) {
        this.orders = orders;
    }

    /** @throws OrderNotFoundException there is no such order, or it belongs to someone else */
    @Transactional(readOnly = true)
    public Order get(UUID id, Caller caller) {
        return orders.findById(id)
                .filter(order -> caller.admin() || caller.owns(order))
                .orElseThrow(() -> new OrderNotFoundException(id));
    }
}
