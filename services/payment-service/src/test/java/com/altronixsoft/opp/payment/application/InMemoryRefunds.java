package com.altronixsoft.opp.payment.application;

import com.altronixsoft.opp.payment.domain.Refund;
import com.altronixsoft.opp.payment.domain.RefundStatus;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** {@link RefundRepository} in memory. */
final class InMemoryRefunds implements RefundRepository {

    final Map<UUID, Refund> stored = new LinkedHashMap<>();

    @Override
    public Optional<Refund> findById(UUID id) {
        return Optional.ofNullable(stored.get(id));
    }

    @Override
    public Optional<Refund> findByRefundRequestId(UUID refundRequestId) {
        return stored.values().stream()
                .filter(r -> r.refundRequestId().equals(refundRequestId))
                .findFirst();
    }

    @Override
    public Optional<Refund> findByStripeRefundId(String stripeRefundId) {
        return stored.values().stream()
                .filter(r -> stripeRefundId.equals(r.stripeRefundId()))
                .findFirst();
    }

    @Override
    public List<Refund> findByPaymentId(UUID paymentId) {
        return stored.values().stream()
                .filter(r -> r.paymentId().equals(paymentId))
                .toList();
    }

    @Override
    public Refund save(Refund refund) {
        stored.put(refund.id(), refund);
        return refund;
    }

    @Override
    public List<Refund> claimDueBatch(RefundStatus status, Instant now, int limit) {
        return stored.values().stream()
                .filter(r -> r.status() == status && r.isDue(now))
                .limit(limit)
                .toList();
    }
}
