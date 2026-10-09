package com.altronixsoft.opp.payment.application;

import com.altronixsoft.opp.payment.domain.Payment;
import com.altronixsoft.opp.payment.domain.PaymentStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port: storage of payments. */
public interface PaymentRepository {

    Optional<Payment> findById(UUID id);

    Optional<Payment> findByOrderId(UUID orderId);

    Optional<Payment> findByStripePaymentIntentId(String paymentIntentId);

    /**
     * Stores a new payment or the changes of a loaded one, with its new status-history entries.
     *
     * @return the stored payment with its current version; use it, not the argument, for further changes
     * @throws PaymentConcurrentlyModifiedException the payment was changed by someone else since it was loaded
     * @throws DuplicatePaymentException a payment for this order or PaymentIntent already exists
     */
    Payment save(Payment payment);

    /**
     * Claims payments with external work due: {@code status} as given and {@code nextAttemptAt <= now}, oldest first, at
     * most {@code limit}. The rows are locked with {@code FOR UPDATE SKIP LOCKED}: concurrent claimers never get the
     * same payment, and a claimer does not wait for rows another one holds.
     *
     * <p>The lock lasts as long as the surrounding transaction, so a claim is only useful together with a lease: call
     * {@link Payment#leaseUntil}, save, and commit; the work is then not due again before the lease ends, while the
     * worker calls Stripe outside any transaction (architecture §7.6, ADR-0008).
     *
     * @throws org.springframework.transaction.IllegalTransactionStateException there is no transaction
     */
    List<Payment> claimDueBatch(PaymentStatus status, Instant now, int limit);

    /**
     * Claims payments for reconciliation (architecture §8.4): {@code REQUIRES_PAYMENT_METHOD}, {@code REQUIRES_ACTION}
     * or {@code PROCESSING}, with a PaymentIntent, not updated since {@code staleBefore} and not reconciled since then,
     * oldest first, at most {@code limit}. They are locked with {@code FOR UPDATE SKIP LOCKED} and marked as reconciled
     * at {@code now}, which keeps other claimers away for the stale period.
     *
     * @return the ids of the claimed payments
     * @throws org.springframework.transaction.IllegalTransactionStateException there is no transaction
     */
    List<UUID> claimForReconciliation(Instant staleBefore, Instant now, int limit);

    /** Undoes the claim of {@link #claimForReconciliation}: the payment is due for the next run again. */
    void releaseReconciliation(UUID paymentId);
}
