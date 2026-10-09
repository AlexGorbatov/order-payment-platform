package com.altronixsoft.opp.payment.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PaymentJpaRepository extends JpaRepository<PaymentEntity, UUID> {

    Optional<PaymentEntity> findByOrderId(UUID orderId);

    Optional<PaymentEntity> findByStripePaymentIntentId(String stripePaymentIntentId);

    /** Native on purpose: {@code FOR UPDATE SKIP LOCKED} has no JPQL form. The index {@code payment_due_idx} serves it. */
    @Query(value = """
                    SELECT * FROM payment
                    WHERE status = :status AND next_attempt_at <= :now
                    ORDER BY next_attempt_at, id
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<PaymentEntity> claimDue(@Param("status") String status, @Param("now") Instant now, @Param("limit") int limit);

    /** Reconciliation candidates, locked; the partial index {@code payment_reconciliation_idx} serves it. */
    @Query(value = """
                    SELECT id FROM payment
                    WHERE status IN ('REQUIRES_PAYMENT_METHOD', 'REQUIRES_ACTION', 'PROCESSING')
                      AND stripe_payment_intent_id IS NOT NULL
                      AND updated_at < :staleBefore
                      AND (last_reconciled_at IS NULL OR last_reconciled_at < :staleBefore)
                    ORDER BY updated_at, id
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<UUID> lockReconciliationCandidates(@Param("staleBefore") Instant staleBefore, @Param("limit") int limit);

    /**
     * {@code last_reconciled_at} is not part of the entity: it is bookkeeping of the reconciliation job, not state of the
     * payment, and must not bump the optimistic-lock version.
     */
    @Modifying
    @Query(value = "UPDATE payment SET last_reconciled_at = :at WHERE id IN (:ids)", nativeQuery = true)
    int markReconciled(@Param("ids") List<UUID> ids, @Param("at") Instant at);

    @Modifying
    @Query(value = "UPDATE payment SET last_reconciled_at = NULL WHERE id = :id", nativeQuery = true)
    int clearReconciled(@Param("id") UUID id);
}
