package com.altronixsoft.opp.order.adapter.out.persistence;

import com.altronixsoft.opp.order.domain.OrderStatus;
import com.altronixsoft.opp.order.domain.TransitionSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Table {@code order_status_history}: append-only. */
@Entity
@Table(name = "order_status_history")
class OrderStatusHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    OrderEntity order;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    OrderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false)
    OrderStatus toStatus;

    @Column(length = 255)
    String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    TransitionSource source;

    @Column(name = "source_event_id")
    UUID sourceEventId;

    @Column(name = "occurred_at", nullable = false)
    Instant occurredAt;

    protected OrderStatusHistoryEntity() {}
}
