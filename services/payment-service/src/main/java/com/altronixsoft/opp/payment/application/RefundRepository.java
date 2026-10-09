package com.altronixsoft.opp.payment.application;

import com.altronixsoft.opp.payment.domain.Refund;
import com.altronixsoft.opp.payment.domain.RefundStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port: storage of refunds. */
public interface RefundRepository {

    Optional<Refund> findById(UUID id);

    Optional<Refund> findByRefundRequestId(UUID refundRequestId);

    Optional<Refund> findByStripeRefundId(String stripeRefundId);

    /** All refunds of a payment, oldest first. */
    List<Refund> findByPaymentId(UUID paymentId);

    /**
     * @return the stored refund with its current version
     * @throws RefundConcurrentlyModifiedException the refund was changed by someone else since it was loaded
     * @throws DuplicateRefundException the refund request, the Stripe refund or the payment's open refund exists
     */
    Refund save(Refund refund);

    /** Same contract as {@link PaymentRepository#claimDueBatch}. */
    List<Refund> claimDueBatch(RefundStatus status, Instant now, int limit);
}
