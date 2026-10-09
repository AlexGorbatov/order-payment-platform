package com.altronixsoft.opp.order.adapter.out.persistence;

import com.altronixsoft.opp.order.domain.CancelReason;
import com.altronixsoft.opp.order.domain.OrderStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Table {@code orders}. A persistence detail: the domain works with {@code Order}, mapped explicitly. */
@Entity
@Table(name = "orders")
class OrderEntity {

    @Id
    UUID id;

    @Column(name = "customer_id", nullable = false)
    String customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    OrderStatus status;

    @Column(nullable = false, length = 3)
    String currency;

    @Column(name = "total_minor", nullable = false)
    long totalMinor;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_reason")
    CancelReason cancelReason;

    @Column(name = "refund_request_id")
    UUID refundRequestId;

    @Column(nullable = false)
    boolean disputed;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    /** Optimistic-locking token; {@code null} until the row has been inserted. */
    @Version
    Long version;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    List<OrderItemEntity> items = new ArrayList<>();

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    List<OrderStatusHistoryEntity> history = new ArrayList<>();

    protected OrderEntity() {}
}
