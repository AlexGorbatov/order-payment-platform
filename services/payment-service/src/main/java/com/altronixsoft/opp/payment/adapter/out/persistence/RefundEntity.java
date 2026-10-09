package com.altronixsoft.opp.payment.adapter.out.persistence;

import com.altronixsoft.opp.payment.domain.RefundStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** Table {@code refund}. */
@Entity
@Table(name = "refund")
class RefundEntity {

    @Id
    UUID id;

    @Column(name = "payment_id", nullable = false)
    UUID paymentId;

    @Column(name = "refund_request_id", nullable = false)
    UUID refundRequestId;

    @Column(name = "amount_minor", nullable = false)
    long amountMinor;

    @Column(nullable = false, length = 3)
    String currency;

    @Column(nullable = false, length = 32)
    String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    RefundStatus status;

    @Column(name = "stripe_refund_id")
    String stripeRefundId;

    @Column(name = "failure_reason", length = 1024)
    String failureReason;

    @Column(nullable = false)
    int attempts;

    @Column(name = "next_attempt_at")
    Instant nextAttemptAt;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    @Version
    Long version;

    protected RefundEntity() {}
}
