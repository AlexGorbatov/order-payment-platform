package com.altronixsoft.opp.order.domain;

import java.util.Objects;

/**
 * A catalog entry. The catalog is the only source of prices: clients send a SKU and a quantity, never a price.
 *
 * @param sku stable identifier, at most 64 characters
 * @param name display name; copied into the order line at the time of ordering
 * @param price unit price, positive
 * @param active inactive products stay in the catalog (old orders refer to them) but cannot be ordered
 */
public record Product(String sku, String name, Money price, boolean active) {

    public Product {
        Objects.requireNonNull(price, "price");
        if (sku == null || sku.isBlank() || sku.length() > OrderItem.MAX_SKU_LENGTH) {
            throw new IllegalArgumentException("sku must be 1.." + OrderItem.MAX_SKU_LENGTH + " characters");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (!price.isPositive()) {
            throw new IllegalArgumentException("price of " + sku + " must be positive");
        }
    }
}
