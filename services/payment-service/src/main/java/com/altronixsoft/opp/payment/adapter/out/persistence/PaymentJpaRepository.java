package com.altronixsoft.opp.payment.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
