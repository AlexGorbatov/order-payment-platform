package com.altronixsoft.opp.payment.adapter.out.persistence;

import com.altronixsoft.opp.payment.application.DuplicateRefundException;
import com.altronixsoft.opp.payment.application.RefundConcurrentlyModifiedException;
import com.altronixsoft.opp.payment.application.RefundRepository;
import com.altronixsoft.opp.payment.domain.Refund;
import com.altronixsoft.opp.payment.domain.RefundStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link RefundRepository} on JPA; see {@link JpaPaymentRepository} for the locking and uniqueness rules. */
@Repository
@Transactional
class JpaRefundRepository implements RefundRepository {

    private final RefundJpaRepository refunds;

    JpaRefundRepository(RefundJpaRepository refunds) {
        this.refunds = refunds;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Refund> findById(UUID id) {
        return refunds.findById(id).map(PaymentMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Refund> findByRefundRequestId(UUID refundRequestId) {
        return refunds.findByRefundRequestId(refundRequestId).map(PaymentMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Refund> findByStripeRefundId(String stripeRefundId) {
        return refunds.findByStripeRefundId(stripeRefundId).map(PaymentMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Refund> findByPaymentId(UUID paymentId) {
        return refunds.findByPaymentIdOrderByCreatedAtAscIdAsc(paymentId).stream()
                .map(PaymentMapper::toDomain)
                .toList();
    }

    @Override
    public Refund save(Refund refund) {
        try {
            RefundEntity entity;
            if (refund.version() == null) {
                entity = PaymentMapper.toNewEntity(refund);
            } else {
                entity = refunds.findById(refund.id())
                        .orElseThrow(() -> new RefundConcurrentlyModifiedException(refund.id()));
                if (!refund.version().equals(entity.version)) {
                    throw new RefundConcurrentlyModifiedException(refund.id());
                }
                PaymentMapper.applyChanges(refund, entity);
            }
            return PaymentMapper.toDomain(refunds.saveAndFlush(entity));
        } catch (OptimisticLockingFailureException e) {
            throw new RefundConcurrentlyModifiedException(refund.id(), e);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateRefundException(Constraints.violatedBy(e), e);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Refund> claimDueBatch(RefundStatus status, Instant now, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        return refunds.claimDue(status.name(), now, limit).stream()
                .map(PaymentMapper::toDomain)
                .toList();
    }
}
