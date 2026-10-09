package com.altronixsoft.opp.order.adapter.in.web;

import com.altronixsoft.opp.order.domain.Order;
import com.altronixsoft.opp.order.domain.OrderItem;
import com.altronixsoft.opp.order.domain.OrderStatus;
import com.altronixsoft.opp.order.domain.StatusChange;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "An order with its lines and status history")
public record OrderResponse(
        UUID id,

        @Schema(
                description = "The owner: the `sub` claim of their token",
                example = "00000000-0000-4000-8000-000000000001")
        String customerId,

        OrderStatus status,
        MoneyResponse total,
        List<Item> items,

        @Schema(description = "A payment dispute was reported for this order")
        boolean disputed,

        Instant createdAt,
        Instant updatedAt,
        @Schema(description = "Oldest first") List<HistoryEntry> history) {

    @Schema(
            description =
                    "One line of the order; name and price were copied from the catalog when the order was placed")
    public record Item(
            @Schema(example = "MUG-JAVA") String sku,
            @Schema(example = "Coffee mug \"Java\"") String name,
            @Schema(example = "2") int quantity,
            MoneyResponse unitPrice,
            MoneyResponse lineTotal) {

        static Item of(OrderItem item) {
            return new Item(
                    item.sku(),
                    item.name(),
                    item.quantity(),
                    MoneyResponse.of(item.unitPrice()),
                    MoneyResponse.of(item.lineTotal()));
        }
    }

    @Schema(description = "One status change (or a dispute, which keeps the status)")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HistoryEntry(
            @Schema(description = "Absent for the creation of the order")
            OrderStatus from,

            OrderStatus to,
            @Schema(example = "CUSTOMER") String reason,
            Instant occurredAt) {

        static HistoryEntry of(StatusChange change) {
            return new HistoryEntry(change.from(), change.to(), change.reason(), change.occurredAt());
        }
    }

    static OrderResponse of(Order order) {
        return new OrderResponse(
                order.id(),
                order.customerId(),
                order.status(),
                MoneyResponse.of(order.total()),
                order.items().stream().map(Item::of).toList(),
                order.disputed(),
                order.createdAt(),
                order.updatedAt(),
                order.history().stream().map(HistoryEntry::of).toList());
    }
}
