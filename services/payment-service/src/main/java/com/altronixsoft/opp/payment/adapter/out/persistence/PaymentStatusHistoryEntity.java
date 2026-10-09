package com.altronixsoft.opp.payment.adapter.out.persistence;

import com.altronixsoft.opp.payment.domain.PaymentStatus;
import com.altronixsoft.opp.payment.domain.PaymentStatusSource;
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

/** Table {@code payment_status_history}: append-only. */
@Entity
@Table(name = "payment_status_history")
class PaymentStatusHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    PaymentEntity payment;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    PaymentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false)
    PaymentStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    PaymentStatusSource source;

    @Column(name = "stripe_event_id")
    String stripeEventId;

    @Column(name = "occurred_at", nullable = false)
    Instant occurredAt;

    protected PaymentStatusHistoryEntity() {}
}
