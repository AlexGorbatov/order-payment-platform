package com.altronixsoft.opp.order.application;

import java.util.List;
import java.util.Objects;

/**
 * What a customer asks for: SKUs and quantities. There is deliberately no price: prices come from the catalog.
 *
 * @param customerId the authenticated customer (JWT subject)
 */
public record PlaceOrderCommand(String customerId, List<Line> lines) {

    public PlaceOrderCommand {
        Objects.requireNonNull(customerId, "customerId");
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
    }

    /** One requested line. */
    public record Line(String sku, int quantity) {}
}
