package com.altronixsoft.opp.order.adapter.in.web;

import com.altronixsoft.opp.order.application.PlaceOrderCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * What a customer sends to place an order: SKUs and quantities only. Prices, names and totals are the server's; a
 * client that sends them anyway is ignored.
 */
@Schema(description = "An order to place. Prices are never accepted from the client; they come from the catalog.")
public record CreateOrderRequest(
        @Schema(
                description = "1 to 20 lines; a SKU may appear on one line only",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull
        @Size(min = 1, max = 20)
        List<@NotNull @Valid Item> items) {

    @Schema(description = "One line of the order")
    public record Item(
            @Schema(description = "Catalog SKU", example = "MUG-JAVA", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank
            @Size(max = 64)
            String sku,

            @Schema(
                    description = "Units, 1 to 10",
                    example = "2",
                    minimum = "1",
                    maximum = "10",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull
            @Min(1)
            @Max(10)
            Integer quantity) {}

    PlaceOrderCommand toCommand(String customerId) {
        return new PlaceOrderCommand(
                customerId,
                items.stream()
                        .map(item -> new PlaceOrderCommand.Line(item.sku(), item.quantity()))
                        .toList());
    }
}
