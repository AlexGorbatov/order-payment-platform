package com.altronixsoft.opp.payment.adapter.out.persistence;

import com.altronixsoft.opp.payment.domain.PaymentStatus;
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

/** Table {@code payment}. A persistence detail: the domain works with {@code Payment}, mapped explicitly. */
@Entity
@Table(name = "payment")
class PaymentEntity {

    @Id
    UUID id;

    @Column(name = "order_id", nullable = false)
    UUID orderId;

    @Column(name = "customer_id", nullable = false)
    String customerId;

    @Column(name = "amount_minor", nullable = false)
    long amountMinor;

    @Column(nullable = false, length = 3)
    String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    PaymentStatus status;

    @Column(name = "stripe_payment_intent_id")
    String stripePaymentIntentId;

    @Column(name = "last_stripe_event_at")
    Instant lastStripeEventAt;

    @Column(name = "last_error_code")
    String lastErrorCode;

    @Column(name = "last_decline_code")
    String lastDeclineCode;

    @Column(name = "last_error_message", length = 1024)
    String lastErrorMessage;

    @Column(name = "cancel_requested", nullable = false)
    boolean cancelRequested;

    @Column(name = "cancel_sent_at")
    Instant cancelSentAt;

    @Column(nullable = false)
    boolean disputed;

    @Column(nullable = false)
    int attempts;

    @Column(name = "next_attempt_at")
    Instant nextAttemptAt;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    @Column(name = "correlation_id")
    UUID correlationId;

    @Column(name = "caused_by_event_id")
    UUID causedByEventId;

    /** Optimistic-locking token; {@code null} until the row has been inserted. */
    @Version
    Long version;

    @OneToMany(mappedBy = "payment", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    List<PaymentStatusHistoryEntity> history = new ArrayList<>();

    protected PaymentEntity() {}
}
