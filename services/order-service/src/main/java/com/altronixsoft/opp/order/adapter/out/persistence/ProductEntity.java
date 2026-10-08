package com.altronixsoft.opp.order.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Table {@code product}: the catalog. Read-only for the service. */
@Entity
@Table(name = "product")
class ProductEntity {

    @Id
    String sku;

    @Column(nullable = false)
    String name;

    @Column(name = "price_minor", nullable = false)
    long priceMinor;

    @Column(nullable = false, length = 3)
    String currency;

    @Column(nullable = false)
    boolean active;

    protected ProductEntity() {}
}
