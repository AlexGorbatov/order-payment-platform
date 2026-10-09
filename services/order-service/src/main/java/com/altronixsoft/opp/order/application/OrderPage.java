package com.altronixsoft.opp.order.application;

import com.altronixsoft.opp.order.domain.Order;
import java.util.List;

/**
 * One page of orders, newest first.
 *
 * @param page zero-based page number
 * @param size requested page size
 * @param totalElements number of orders over all pages
 */
public record OrderPage(List<Order> content, int page, int size, long totalElements) {

    public OrderPage {
        content = List.copyOf(content);
    }

    public int totalPages() {
        return (int) ((totalElements + size - 1) / size);
    }
}
