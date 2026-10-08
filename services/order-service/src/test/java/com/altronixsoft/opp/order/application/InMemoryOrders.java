package com.altronixsoft.opp.order.application;

import com.altronixsoft.opp.order.domain.Order;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** {@link OrderRepository} in memory, for the unit tests of the use cases. */
final class InMemoryOrders implements OrderRepository {

    final Map<UUID, Order> stored = new HashMap<>();
    int saves;
    RuntimeException failOnSave;

    @Override
    public Optional<Order> findById(UUID id) {
        return Optional.ofNullable(stored.get(id));
    }

    @Override
    public OrderPage findPage(int page, int size) {
        return page(stored.values().stream().toList(), page, size);
    }

    @Override
    public OrderPage findPageByCustomer(String customerId, int page, int size) {
        return page(
                stored.values().stream()
                        .filter(o -> o.customerId().equals(customerId))
                        .toList(),
                page,
                size);
    }

    @Override
    public Order save(Order order) {
        if (failOnSave != null) {
            throw failOnSave;
        }
        saves++;
        stored.put(order.id(), order);
        return order;
    }

    private static OrderPage page(List<Order> all, int page, int size) {
        List<Order> sorted = all.stream()
                .sorted(Comparator.comparing(Order::createdAt).reversed())
                .toList();
        int from = Math.min(page * size, sorted.size());
        int to = Math.min(from + size, sorted.size());
        return new OrderPage(sorted.subList(from, to), page, size, sorted.size());
    }
}
