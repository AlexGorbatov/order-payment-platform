package com.altronixsoft.opp.payment.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RefundJpaRepository extends JpaRepository<RefundEntity, UUID> {

    Optional<RefundEntity> findByRefundRequestId(UUID refundRequestId);

    Optional<RefundEntity> findByStripeRefundId(String stripeRefundId);

    List<RefundEntity> findByPaymentIdOrderByCreatedAtAscIdAsc(UUID paymentId);

    @Query(value = """
                    SELECT * FROM refund
                    WHERE status = :status AND next_attempt_at <= :now
                    ORDER BY next_attempt_at, id
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<RefundEntity> claimDue(@Param("status") String status, @Param("now") Instant now, @Param("limit") int limit);
}
