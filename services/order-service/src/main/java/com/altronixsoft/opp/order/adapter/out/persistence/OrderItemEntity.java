package com.altronixsoft.opp.order.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Table {@code order_item}: a line with the product name and price as they were when the order was placed. */
@Entity
@Table(name = "order_item")
class OrderItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    OrderEntity order;

    @Column(nullable = false, length = 64)
    String sku;

    @Column(nullable = false)
    String name;

    @Column(nullable = false)
    int quantity;

    @Column(name = "unit_price_minor", nullable = false)
    long unitPriceMinor;

    @Column(name = "line_total_minor", nullable = false)
    long lineTotalMinor;

    protected OrderItemEntity() {}
}
