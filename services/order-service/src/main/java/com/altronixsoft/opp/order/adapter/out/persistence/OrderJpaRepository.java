package com.altronixsoft.opp.order.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface OrderJpaRepository extends JpaRepository<OrderEntity, UUID> {

    Page<OrderEntity> findByCustomerId(String customerId, Pageable pageable);

    /** Ids of overdue unpaid orders, oldest first, row-locked; rows locked elsewhere are skipped. */
    @Query(value = """
                    SELECT id FROM orders
                    WHERE status = 'PENDING_PAYMENT' AND created_at < :placedBefore
                    ORDER BY created_at, id
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<UUID> lockOverduePendingPaymentIds(Instant placedBefore, int limit);
}
