package com.altronixsoft.opp.payment.adapter.out.persistence;

import com.altronixsoft.opp.payment.application.DuplicatePaymentException;
import com.altronixsoft.opp.payment.application.PaymentConcurrentlyModifiedException;
import com.altronixsoft.opp.payment.application.PaymentRepository;
import com.altronixsoft.opp.payment.domain.Payment;
import com.altronixsoft.opp.payment.domain.PaymentStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link PaymentRepository} on JPA.
 *
 * <p>Optimistic locking is checked twice, as in the order service: {@code save} compares the version it was given with
 * the stored row, and the {@code @Version} column guards the flush itself. Uniqueness (one payment per order, one per
 * PaymentIntent) is the database's: a violation surfaces as {@link DuplicatePaymentException}.
 */
@Repository
@Transactional
class JpaPaymentRepository implements PaymentRepository {

    private final PaymentJpaRepository payments;

    JpaPaymentRepository(PaymentJpaRepository payments) {
        this.payments = payments;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Payment> findById(UUID id) {
        return payments.findById(id).map(PaymentMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Payment> findByOrderId(UUID orderId) {
        return payments.findByOrderId(orderId).map(PaymentMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Payment> findByStripePaymentIntentId(String paymentIntentId) {
        return payments.findByStripePaymentIntentId(paymentIntentId).map(PaymentMapper::toDomain);
    }

    @Override
    public Payment save(Payment payment) {
        try {
            PaymentEntity entity;
            if (payment.version() == null) {
                entity = PaymentMapper.toNewEntity(payment);
            } else {
                entity = payments.findById(payment.id())
                        .orElseThrow(() -> new PaymentConcurrentlyModifiedException(payment.id()));
                if (!payment.version().equals(entity.version)) {
                    throw new PaymentConcurrentlyModifiedException(payment.id());
                }
                PaymentMapper.applyChanges(payment, entity);
            }
            return PaymentMapper.toDomain(payments.saveAndFlush(entity));
        } catch (OptimisticLockingFailureException e) {
            throw new PaymentConcurrentlyModifiedException(payment.id(), e);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicatePaymentException(Constraints.violatedBy(e), e);
        }
    }

    /** {@code MANDATORY}: the row locks only mean something inside the caller's transaction. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Payment> claimDueBatch(PaymentStatus status, Instant now, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        return payments.claimDue(status.name(), now, limit).stream()
                .map(PaymentMapper::toDomain)
                .toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> claimForReconciliation(Instant staleBefore, Instant now, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        List<UUID> ids = payments.lockReconciliationCandidates(staleBefore, limit);
        if (!ids.isEmpty()) {
            payments.markReconciled(ids, now);
        }
        return ids;
    }

    @Override
    public void releaseReconciliation(UUID paymentId) {
        payments.clearReconciled(paymentId);
    }
}
