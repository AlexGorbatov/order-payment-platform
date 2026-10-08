package com.altronixsoft.opp.order.application;

import java.util.List;

/** Some requested SKUs are not in the catalog or are no longer sold. */
public class ProductNotAvailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final List<String> skus;

    public ProductNotAvailableException(List<String> skus) {
        super("Products not available: " + String.join(", ", skus));
        this.skus = List.copyOf(skus);
    }

    /** The unknown or inactive SKUs, sorted. */
    public List<String> skus() {
        return skus;
    }
}
