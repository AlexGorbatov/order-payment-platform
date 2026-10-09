package com.altronixsoft.opp.payment.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface StripeWebhookEventJpaRepository extends JpaRepository<StripeWebhookEventEntity, String> {

    /** @return 1 if the row was inserted, 0 if an event with this id already exists */
    @Modifying
    @Query(value = """
                    INSERT INTO stripe_webhook_event
                        (event_id, type, api_version, livemode, stripe_created_at, payload, status, attempts,
                         next_attempt_at, last_error, received_at, processed_at)
                    VALUES
                        (:eventId, :type, :apiVersion, :livemode, :stripeCreatedAt, CAST(:payload AS jsonb), :status,
                         :attempts, :nextAttemptAt, :lastError, :receivedAt, :processedAt)
                    ON CONFLICT (event_id) DO NOTHING""", nativeQuery = true)
    int insertIfAbsent(
            @Param("eventId") String eventId,
            @Param("type") String type,
            @Param("apiVersion") String apiVersion,
            @Param("livemode") boolean livemode,
            @Param("stripeCreatedAt") Instant stripeCreatedAt,
            @Param("payload") String payload,
            @Param("status") String status,
            @Param("attempts") int attempts,
            @Param("nextAttemptAt") Instant nextAttemptAt,
            @Param("lastError") String lastError,
            @Param("receivedAt") Instant receivedAt,
            @Param("processedAt") Instant processedAt);

    @Query(value = """
                    SELECT * FROM stripe_webhook_event
                    WHERE status IN ('RECEIVED', 'FAILED') AND next_attempt_at <= :now
                    ORDER BY next_attempt_at, event_id
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<StripeWebhookEventEntity> claimDue(@Param("now") Instant now, @Param("limit") int limit);
}
