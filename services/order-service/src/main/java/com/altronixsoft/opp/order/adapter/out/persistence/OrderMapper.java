package com.altronixsoft.opp.order.adapter.out.persistence;

import com.altronixsoft.opp.order.domain.Money;
import com.altronixsoft.opp.order.domain.Order;
import com.altronixsoft.opp.order.domain.OrderItem;
import com.altronixsoft.opp.order.domain.Product;
import com.altronixsoft.opp.order.domain.StatusChange;
import java.util.List;

/**
 * Explicit mapping between the domain and the JPA entities, in both directions. No reflection-based mapper: every field
 * is visible here, so a change of the domain or the schema cannot slip through silently.
 */
final class OrderMapper {

    private static final int MAX_REASON_LENGTH = 255;

    private OrderMapper() {}

    // ------------------------------------------------------------------------------------------ entity -> domain

    static Order toDomain(OrderEntity entity) {
        List<OrderItem> items = entity.items.stream()
                .map(item -> new OrderItem(
                        item.sku, item.name, item.quantity, Money.of(item.unitPriceMinor, entity.currency)))
                .toList();
        List<StatusChange> history = entity.history.stream()
                .map(change -> new StatusChange(
                        change.fromStatus,
                        change.toStatus,
                        change.reason,
                        change.source,
                        change.sourceEventId,
                        change.occurredAt))
                .toList();
        return Order.restore(
                entity.id,
                entity.customerId,
                items,
                Money.of(entity.totalMinor, entity.currency),
                entity.status,
                entity.cancelReason,
                entity.disputed,
                entity.createdAt,
                entity.updatedAt,
                entity.version,
                history);
    }

    static Product toDomain(ProductEntity entity) {
        return new Product(entity.sku, entity.name, Money.of(entity.priceMinor, entity.currency), entity.active);
    }

    // ------------------------------------------------------------------------------------------ domain -> entity

    /** A new row for an order that has never been stored. */
    static OrderEntity toNewEntity(Order order) {
        OrderEntity entity = new OrderEntity();
        entity.id = order.id();
        entity.customerId = order.customerId();
        entity.currency = order.total().currencyCode();
        entity.totalMinor = order.total().amountMinor();
        entity.createdAt = order.createdAt();
        for (OrderItem item : order.items()) {
            OrderItemEntity row = new OrderItemEntity();
            row.order = entity;
            row.sku = item.sku();
            row.name = item.name();
            row.quantity = item.quantity();
            row.unitPriceMinor = item.unitPrice().amountMinor();
            row.lineTotalMinor = item.lineTotal().amountMinor();
            entity.items.add(row);
        }
        applyChanges(order, entity);
        return entity;
    }

    /**
     * Copies what can change after creation — status, cancel reason, dispute flag, update time — onto a loaded entity,
     * and appends the history entries it does not have yet. Lines never change and the history is append-only.
     */
    static void applyChanges(Order order, OrderEntity entity) {
        entity.status = order.status();
        entity.cancelReason = order.cancelReason();
        entity.disputed = order.disputed();
        entity.updatedAt = order.updatedAt();
        List<StatusChange> history = order.history();
        for (StatusChange change : history.subList(entity.history.size(), history.size())) {
            OrderStatusHistoryEntity row = new OrderStatusHistoryEntity();
            row.order = entity;
            row.fromStatus = change.from();
            row.toStatus = change.to();
            row.reason = truncate(change.reason());
            row.source = change.source();
            row.sourceEventId = change.sourceEventId();
            row.occurredAt = change.occurredAt();
            entity.history.add(row);
        }
    }

    private static String truncate(String reason) {
        return reason == null || reason.length() <= MAX_REASON_LENGTH ? reason : reason.substring(0, MAX_REASON_LENGTH);
    }
}
