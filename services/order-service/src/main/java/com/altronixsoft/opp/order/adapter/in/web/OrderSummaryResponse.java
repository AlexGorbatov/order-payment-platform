package com.altronixsoft.opp.order.adapter.in.web;

import com.altronixsoft.opp.order.domain.Order;
import com.altronixsoft.opp.order.domain.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "An order in a list")
public record OrderSummaryResponse(
        UUID id,

        @Schema(example = "00000000-0000-4000-8000-000000000001")
        String customerId,

        OrderStatus status,
        MoneyResponse total,

        @Schema(description = "Number of lines", example = "2")
        int lineCount,

        boolean disputed,
        Instant createdAt,
        Instant updatedAt) {

    static OrderSummaryResponse of(Order order) {
        return new OrderSummaryResponse(
                order.id(),
                order.customerId(),
                order.status(),
                MoneyResponse.of(order.total()),
                order.items().size(),
                order.disputed(),
                order.createdAt(),
                order.updatedAt());
    }
}
