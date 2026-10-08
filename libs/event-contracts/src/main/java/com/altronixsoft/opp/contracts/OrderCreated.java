package com.altronixsoft.opp.contracts;

import java.util.UUID;

/** An order was placed and awaits payment. */
public record OrderCreated(UUID orderId, String customerId, long amountMinor, String currency, int itemCount)
        implements OrderEvent {

    public OrderCreated {
        Fields.require(orderId, "orderId");
        Fields.require(customerId, "customerId");
        if (customerId.isBlank()) {
            throw new IllegalArgumentException("customerId must not be blank");
        }
        Currencies.requirePositive(amountMinor, "amountMinor");
        Currencies.requireIso4217(currency);
        if (itemCount <= 0) {
            throw new IllegalArgumentException("itemCount must be positive, got " + itemCount);
        }
    }
}
