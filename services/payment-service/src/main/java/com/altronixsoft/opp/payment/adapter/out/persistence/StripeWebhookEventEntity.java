package com.altronixsoft.opp.payment.adapter.out.persistence;

import com.altronixsoft.opp.payment.domain.WebhookEventStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Table {@code stripe_webhook_event}. Rows are inserted by a native statement ({@code ON CONFLICT DO NOTHING}). */
@Entity
@Table(name = "stripe_webhook_event")
class StripeWebhookEventEntity {

    @Id
    @Column(name = "event_id")
    String eventId;

    @Column(nullable = false, length = 128)
    String type;

    @Column(name = "api_version", length = 32)
    String apiVersion;

    @Column(nullable = false)
    boolean livemode;

    @Column(name = "stripe_created_at", nullable = false)
    Instant stripeCreatedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    WebhookEventStatus status;

    @Column(nullable = false)
    int attempts;

    @Column(name = "next_attempt_at")
    Instant nextAttemptAt;

    @Column(name = "last_error", length = 1024)
    String lastError;

    @Column(name = "received_at", nullable = false)
    Instant receivedAt;

    @Column(name = "processed_at")
    Instant processedAt;

    protected StripeWebhookEventEntity() {}
}
