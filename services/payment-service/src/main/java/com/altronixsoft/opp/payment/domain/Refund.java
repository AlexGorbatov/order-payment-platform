package com.altronixsoft.opp.payment.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * A full refund of a payment (architecture §5.3). One per {@code refundRequestId}; an administrator who retries a failed
 * refund sends a new request id and so creates a new refund. Created {@code REQUESTED} and due for the refund worker;
 * the worker creates it at Stripe ({@link #markPending}), a webhook settles it ({@link #markSucceeded},
 * {@link #markFailed}). The work-queue behaviour is the one described at {@link Payment}.
 */
public final class Refund {

    private final UUID id;
    private final UUID paymentId;
    private final UUID refundRequestId;
    private final Money amount;
    private final String reason;
    private final Instant createdAt;
    private final Long version;
    private final UUID correlationId;
    private final UUID causedByEventId;

    private RefundStatus status;
    private String stripeRefundId;
    private String failureReason;
    private int attempts;
    private Instant nextAttemptAt;
    private Instant updatedAt;

    private Refund(
            UUID id,
            UUID paymentId,
            UUID refundRequestId,
            Money amount,
            String reason,
            RefundStatus status,
            String stripeRefundId,
            String failureReason,
            int attempts,
            Instant nextAttemptAt,
            Instant createdAt,
            Instant updatedAt,
            Long version,
            UUID correlationId,
            UUID causedByEventId) {
        this.id = id;
        this.paymentId = paymentId;
        this.refundRequestId = refundRequestId;
        this.amount = amount;
        this.reason = reason;
        this.status = status;
        this.stripeRefundId = stripeRefundId;
        this.failureReason = failureReason;
        this.attempts = attempts;
        this.nextAttemptAt = nextAttemptAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
        this.correlationId = correlationId;
        this.causedByEventId = causedByEventId;
    }

    /**
     * A new refund, {@link RefundStatus#REQUESTED} and due immediately.
     *
     * @param reason why (the order's refund reason, for example {@code ADMIN})
     * @throws InvalidPaymentException the amount is not positive or the reason is blank
     */
    public static Refund request(
            UUID id, UUID paymentId, UUID refundRequestId, Money amount, String reason, Instant now) {
        return request(id, paymentId, refundRequestId, amount, reason, now, null, null);
    }

    /**
     * Same as above, remembering the business flow: the events published when the refund is settled carry
     * {@code correlationId} and name {@code causedByEventId}, the consumed {@code OrderRefundRequested}, as their cause.
     */
    public static Refund request(
            UUID id,
            UUID paymentId,
            UUID refundRequestId,
            Money amount,
            String reason,
            Instant now,
            UUID correlationId,
            UUID causedByEventId) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(refundRequestId, "refundRequestId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(now, "now");
        if (!amount.isPositive()) {
            throw new InvalidPaymentException("a refund needs a positive amount, got " + amount);
        }
        if (reason == null || reason.isBlank()) {
            throw new InvalidPaymentException("a refund needs a reason");
        }
        return new Refund(
                id,
                paymentId,
                refundRequestId,
                amount,
                reason,
                RefundStatus.REQUESTED,
                null,
                null,
                0,
                now,
                now,
                now,
                null,
                correlationId,
                causedByEventId);
    }

    /** Rebuilds a refund from storage. Nothing is validated. */
    public static Refund restore(
            UUID id,
            UUID paymentId,
            UUID refundRequestId,
            Money amount,
            String reason,
            RefundStatus status,
            String stripeRefundId,
            String failureReason,
            int attempts,
            Instant nextAttemptAt,
            Instant createdAt,
            Instant updatedAt,
            Long version,
            UUID correlationId,
            UUID causedByEventId) {
        return new Refund(
                id,
                paymentId,
                refundRequestId,
                amount,
                reason,
                status,
                stripeRefundId,
                failureReason,
                attempts,
                nextAttemptAt,
                createdAt,
                updatedAt,
                version,
                correlationId,
                causedByEventId);
    }

    /** The refund exists at Stripe: {@code REQUESTED → PENDING}; the creation work is done. */
    public void markPending(String stripeRefundId, Instant now) {
        if (stripeRefundId == null || stripeRefundId.isBlank()) {
            throw new IllegalArgumentException("stripeRefundId must not be blank");
        }
        move(RefundStatus.PENDING, "mark pending", now);
        this.stripeRefundId = stripeRefundId;
    }

    /** Stripe says the money is on its way back: {@code PENDING → SUCCEEDED}. */
    public void markSucceeded(Instant now) {
        move(RefundStatus.SUCCEEDED, "succeed", now);
    }

    /** The refund failed, at creation or later: {@code REQUESTED | PENDING → FAILED}. */
    public void markFailed(String failureReason, Instant now) {
        move(RefundStatus.FAILED, "fail", now);
        this.failureReason = truncate(failureReason);
    }

    private void move(RefundStatus target, String action, Instant now) {
        Objects.requireNonNull(now, "now");
        if (!status.canTransitionTo(target)) {
            throw new IllegalRefundTransitionException(id, status, action);
        }
        status = target;
        updatedAt = now;
        nextAttemptAt = null;
        attempts = 0;
    }

    // ------------------------------------------------------------------------------------------ work queue

    public boolean isDue(Instant now) {
        return nextAttemptAt != null && !nextAttemptAt.isAfter(now);
    }

    /** See {@link Payment#leaseUntil}. */
    public void leaseUntil(Instant until) {
        Objects.requireNonNull(until, "until");
        if (nextAttemptAt == null) {
            throw new IllegalStateException("Refund " + id + " has no work to lease");
        }
        nextAttemptAt = until;
    }

    /** See {@link Payment#scheduleRetry}; on exhaustion the caller marks the refund failed. */
    public RetryDecision scheduleRetry(Instant now, RetryPolicy policy, RandomGenerator random, String errorMessage) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(random, "random");
        if (status != RefundStatus.REQUESTED) {
            throw new IllegalRefundTransitionException(id, status, "retry");
        }
        attempts++;
        failureReason = truncate(errorMessage);
        updatedAt = now;
        if (policy.isExhaustedAfter(attempts)) {
            nextAttemptAt = null;
            return RetryDecision.EXHAUSTED;
        }
        nextAttemptAt = policy.nextAttemptAfter(attempts, now, random);
        return RetryDecision.RETRY_SCHEDULED;
    }

    private static String truncate(String message) {
        return message == null || message.length() <= Payment.MAX_ERROR_MESSAGE_LENGTH
                ? message
                : message.substring(0, Payment.MAX_ERROR_MESSAGE_LENGTH);
    }

    // ------------------------------------------------------------------------------------------ state

    public UUID id() {
        return id;
    }

    public UUID paymentId() {
        return paymentId;
    }

    public UUID refundRequestId() {
        return refundRequestId;
    }

    public Money amount() {
        return amount;
    }

    public String reason() {
        return reason;
    }

    public RefundStatus status() {
        return status;
    }

    public String stripeRefundId() {
        return stripeRefundId;
    }

    public String failureReason() {
        return failureReason;
    }

    public int attempts() {
        return attempts;
    }

    public Instant nextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Long version() {
        return version;
    }

    /** The business flow this refund belongs to; may be {@code null}. */
    public UUID correlationId() {
        return correlationId;
    }

    /** The event that created this refund ({@code OrderRefundRequested}); may be {@code null}. */
    public UUID causedByEventId() {
        return causedByEventId;
    }
}
