package com.altronixsoft.opp.order.domain;

import java.util.Objects;

/**
 * One line of an order: a product at the price it had when the order was placed.
 *
 * @param sku product identifier
 * @param name product name at the time of ordering
 * @param quantity 1 to {@value #MAX_QUANTITY}
 * @param unitPrice price per unit from the catalog, positive
 */
public record OrderItem(String sku, String name, int quantity, Money unitPrice) {

    public static final int MAX_QUANTITY = 10;
    public static final int MAX_SKU_LENGTH = 64;

    public OrderItem {
        Objects.requireNonNull(unitPrice, "unitPrice");
        if (sku == null || sku.isBlank() || sku.length() > MAX_SKU_LENGTH) {
            throw new InvalidOrderException("sku must be 1.." + MAX_SKU_LENGTH + " characters");
        }
        if (name == null || name.isBlank()) {
            throw new InvalidOrderException("name of " + sku + " must not be blank");
        }
        if (quantity < 1 || quantity > MAX_QUANTITY) {
            throw new InvalidOrderException(
                    "quantity of " + sku + " must be between 1 and " + MAX_QUANTITY + ", got " + quantity);
        }
        if (!unitPrice.isPositive()) {
            throw new InvalidOrderException("unit price of " + sku + " must be positive");
        }
    }

    /** Price of the line: unit price times quantity. */
    public Money lineTotal() {
        return unitPrice.times(quantity);
    }
}
