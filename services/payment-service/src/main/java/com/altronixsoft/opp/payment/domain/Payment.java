package com.altronixsoft.opp.payment.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * One payment for one order (architecture §5.2). Plain Java, no framework.
 *
 * <h2>Two kinds of change</h2>
 *
 * <ul>
 *   <li>What <em>Stripe reports</em> (a webhook, an API response, reconciliation) goes through
 *       {@link #applyStripeStatus} and obeys the ordering rule of §8.3: it is applied only if it is not older than the
 *       last applied report ({@code observedAt >= lastStripeEventAt}) <em>and</em> the transition is allowed. Anything
 *       else is {@link StripeOutcome#STALE_IGNORED}, an ordinary outcome, not an exception: webhooks arrive late, twice
 *       and out of order.
 *   <li>What <em>we decide</em> ({@link #create}, {@link #attachPaymentIntent}, {@link #markInitiationFailed},
 *       {@link #requestCancel}) are commands; one that does not fit the status throws
 *       {@link IllegalPaymentTransitionException}.
 * </ul>
 *
 * <h2>Work queue</h2>
 *
 * {@code nextAttemptAt != null} means the payment has external work due then: creating the PaymentIntent while
 * {@code CREATED}, cancelling it while {@code cancelRequested} (architecture §7.6). A worker claims due payments, takes a
 * lease with {@link #leaseUntil}, calls Stripe outside any transaction, and reports with {@link #scheduleRetry},
 * {@link #attachPaymentIntent}, {@link #markCancelSent} or a Stripe status. Reaching a status with nothing left to do
 * clears the work.
 *
 * <p>Concurrency is the repository's business ({@code version}); the aggregate itself is not thread-safe.
 */
public final class Payment {

    public static final int MAX_ERROR_MESSAGE_LENGTH = 1024;

    private final UUID id;
    private final UUID orderId;
    private final String customerId;
    private final Money amount;
    private final Instant createdAt;
    private final Long version;
    private final UUID correlationId;
    private final UUID causedByEventId;
    private final List<PaymentStatusChange> history;
    private final List<PaymentDomainEvent> domainEvents = new ArrayList<>();

    private PaymentStatus status;
    private String stripePaymentIntentId;
    private Instant lastStripeEventAt;
    private String lastErrorCode;
    private String lastErrorMessage;
    private boolean cancelRequested;
    private Instant cancelSentAt;
    private boolean disputed;
    private int attempts;
    private Instant nextAttemptAt;
    private Instant updatedAt;

    private Payment(
            UUID id,
            UUID orderId,
            String customerId,
            Money amount,
            PaymentStatus status,
            String stripePaymentIntentId,
            Instant lastStripeEventAt,
            String lastErrorCode,
            String lastErrorMessage,
            boolean cancelRequested,
            Instant cancelSentAt,
            boolean disputed,
            int attempts,
            Instant nextAttemptAt,
            Instant createdAt,
            Instant updatedAt,
            Long version,
            UUID correlationId,
            UUID causedByEventId,
            List<PaymentStatusChange> history) {
        this.id = id;
        this.orderId = orderId;
        this.customerId = customerId;
        this.amount = amount;
        this.status = status;
        this.stripePaymentIntentId = stripePaymentIntentId;
        this.lastStripeEventAt = lastStripeEventAt;
        this.lastErrorCode = lastErrorCode;
        this.lastErrorMessage = lastErrorMessage;
        this.cancelRequested = cancelRequested;
        this.cancelSentAt = cancelSentAt;
        this.disputed = disputed;
        this.attempts = attempts;
        this.nextAttemptAt = nextAttemptAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
        this.correlationId = correlationId;
        this.causedByEventId = causedByEventId;
        this.history = new ArrayList<>(history);
    }

    // ------------------------------------------------------------------------------------------ creation

    /**
     * A new payment for an order, {@link PaymentStatus#CREATED} and due for PaymentIntent creation immediately.
     *
     * @throws InvalidPaymentException the amount is not positive or the customer is blank
     */
    public static Payment create(UUID id, UUID orderId, String customerId, Money amount, Instant now) {
        return create(id, orderId, customerId, amount, now, null, null);
    }

    /**
     * Same as above, remembering the business flow the payment belongs to: the events published later (when the worker
     * has created the PaymentIntent) carry this {@code correlationId} and name {@code causedByEventId}, the consumed
     * {@code OrderCreated}, as their cause.
     */
    public static Payment create(
            UUID id,
            UUID orderId,
            String customerId,
            Money amount,
            Instant now,
            UUID correlationId,
            UUID causedByEventId) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(now, "now");
        if (customerId == null || customerId.isBlank()) {
            throw new InvalidPaymentException("customerId must not be blank");
        }
        if (!amount.isPositive()) {
            throw new InvalidPaymentException("a payment needs a positive amount, got " + amount);
        }
        Payment payment = new Payment(
                id,
                orderId,
                customerId,
                amount,
                PaymentStatus.CREATED,
                null,
                null,
                null,
                null,
                false,
                null,
                false,
                0,
                now,
                now,
                now,
                null,
                correlationId,
                causedByEventId,
                List.of());
        payment.history.add(new PaymentStatusChange(null, PaymentStatus.CREATED, PaymentStatusSource.LOCAL, null, now));
        return payment;
    }

    /** Rebuilds a payment from storage. Nothing is validated or recorded. */
    public static Payment restore(
            UUID id,
            UUID orderId,
            String customerId,
            Money amount,
            PaymentStatus status,
            String stripePaymentIntentId,
            Instant lastStripeEventAt,
            String lastErrorCode,
            String lastErrorMessage,
            boolean cancelRequested,
            Instant cancelSentAt,
            boolean disputed,
            int attempts,
            Instant nextAttemptAt,
            Instant createdAt,
            Instant updatedAt,
            Long version,
            UUID correlationId,
            UUID causedByEventId,
            List<PaymentStatusChange> history) {
        return new Payment(
                id,
                orderId,
                customerId,
                amount,
                status,
                stripePaymentIntentId,
                lastStripeEventAt,
                lastErrorCode,
                lastErrorMessage,
                cancelRequested,
                cancelSentAt,
                disputed,
                attempts,
                nextAttemptAt,
                createdAt,
                updatedAt,
                version,
                correlationId,
                causedByEventId,
                history);
    }

    // ------------------------------------------------------------------------------------------ what Stripe reports

    /** Same as the four-argument form, for a report without a webhook event id (an API response, reconciliation). */
    public StripeOutcome applyStripeStatus(String stripeStatus, Instant observedAt, PaymentStatusSource source) {
        return applyStripeStatus(stripeStatus, observedAt, source, null);
    }

    /**
     * Applies a PaymentIntent status reported by Stripe, if it is current and allowed (architecture §8.3).
     *
     * @param stripeStatus the {@code PaymentIntent.status}, mapped by {@link StripePaymentIntentStatus}
     * @param observedAt when Stripe says it was true ({@code event.created} for a webhook); events of the same second
     *     are not older than each other, so a late report of the same second is judged by the transition rule alone
     * @param source where the report came from; not {@link PaymentStatusSource#LOCAL}
     * @param stripeEventId the webhook event, recorded in the history; may be {@code null}
     * @throws StripeConfigurationException {@code requires_capture}
     * @throws UnknownStripeStatusException a status without a mapping
     */
    public StripeOutcome applyStripeStatus(
            String stripeStatus, Instant observedAt, PaymentStatusSource source, String stripeEventId) {
        return applyStatus(StripePaymentIntentStatus.toPaymentStatus(stripeStatus), observedAt, source, stripeEventId);
    }

    /**
     * The full refund succeeded ({@code charge.refunded}): {@code SUCCEEDED → REFUNDED}, under the same ordering rule
     * as a PaymentIntent status.
     */
    public StripeOutcome markRefunded(Instant observedAt, PaymentStatusSource source, String stripeEventId) {
        return applyStatus(PaymentStatus.REFUNDED, observedAt, source, stripeEventId);
    }

    private StripeOutcome applyStatus(
            PaymentStatus target, Instant observedAt, PaymentStatusSource source, String stripeEventId) {
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(source, "source");
        if (!source.isFromStripe()) {
            throw new IllegalArgumentException("a Stripe report cannot have the source " + source);
        }
        if (lastStripeEventAt != null && observedAt.isBefore(lastStripeEventAt)) {
            return StripeOutcome.STALE_IGNORED;
        }
        if (target == status) {
            lastStripeEventAt = observedAt;
            updatedAt = observedAt;
            return StripeOutcome.UNCHANGED;
        }
        if (!status.canTransitionTo(target)) {
            return StripeOutcome.STALE_IGNORED;
        }
        moveTo(target, source, stripeEventId, observedAt);
        lastStripeEventAt = observedAt;
        return StripeOutcome.APPLIED;
    }

    /**
     * A PaymentIntent was disputed. Sets the flag in any status; repeating it does nothing.
     *
     * @return whether the flag changed
     */
    public boolean markDisputed(Instant now) {
        Objects.requireNonNull(now, "now");
        if (disputed) {
            return false;
        }
        disputed = true;
        updatedAt = now;
        return true;
    }

    /** Remembers the latest error shown for this payment (a decline code, a Stripe API failure). */
    public void recordError(String code, String message, Instant now) {
        Objects.requireNonNull(now, "now");
        lastErrorCode = code;
        lastErrorMessage = truncate(message);
        updatedAt = now;
    }

    // ------------------------------------------------------------------------------------------ what we decide

    /**
     * The PaymentIntent was created at Stripe: {@code CREATED → REQUIRES_PAYMENT_METHOD}, and the creation work is done.
     *
     * @param createdAt the PaymentIntent's {@code created}; the ordering watermark starts there
     * @throws IllegalPaymentTransitionException the payment is not {@code CREATED} (it was cancelled while the worker
     *     was calling Stripe: the caller must cancel the orphaned PaymentIntent)
     */
    public void attachPaymentIntent(String paymentIntentId, Instant createdAt) {
        if (paymentIntentId == null || paymentIntentId.isBlank()) {
            throw new IllegalArgumentException("paymentIntentId must not be blank");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        requireStatus(PaymentStatus.CREATED, "attach a PaymentIntent");
        stripePaymentIntentId = paymentIntentId;
        moveTo(PaymentStatus.REQUIRES_PAYMENT_METHOD, PaymentStatusSource.STRIPE_API, null, createdAt);
        lastStripeEventAt = createdAt;
        domainEvents.add(new PaymentDomainEvent.Initiated(id, orderId, paymentIntentId, createdAt));
    }

    /**
     * Stripe permanently refused to create the PaymentIntent, or the idempotency-key window expired:
     * {@code CREATED → INITIATION_FAILED}.
     */
    public void markInitiationFailed(String errorCode, String errorMessage, Instant now) {
        Objects.requireNonNull(now, "now");
        requireStatus(PaymentStatus.CREATED, "fail its initiation");
        lastErrorCode = errorCode;
        lastErrorMessage = truncate(errorMessage);
        moveTo(PaymentStatus.INITIATION_FAILED, PaymentStatusSource.LOCAL, null, now);
        domainEvents.add(new PaymentDomainEvent.InitiationFailed(
                id, orderId, errorCode == null || errorCode.isBlank() ? "unknown" : errorCode, now));
    }

    /**
     * Asks the payment to cancel. Before a PaymentIntent exists it is cancelled on the spot; afterwards the cancellation
     * worker is scheduled, and the payment becomes {@code CANCELED} when Stripe reports it. Too late (processing,
     * succeeded, ...) it does nothing.
     */
    public CancelRequest requestCancel(Instant now) {
        Objects.requireNonNull(now, "now");
        if (status == PaymentStatus.CANCELED) {
            return CancelRequest.ALREADY_CANCELED;
        }
        if (status == PaymentStatus.CREATED) {
            moveTo(PaymentStatus.CANCELED, PaymentStatusSource.LOCAL, null, now);
            return CancelRequest.CANCELED_LOCALLY;
        }
        if (!status.isCancelableAtStripe()) {
            return CancelRequest.NOT_CANCELABLE;
        }
        if (cancelRequested) {
            return CancelRequest.ALREADY_REQUESTED;
        }
        cancelRequested = true;
        attempts = 0;
        nextAttemptAt = now;
        updatedAt = now;
        return CancelRequest.CANCEL_SCHEDULED;
    }

    /** The cancellation request reached Stripe; the cancellation work is done. */
    public void markCancelSent(Instant now) {
        Objects.requireNonNull(now, "now");
        cancelSentAt = now;
        clearWork();
        updatedAt = now;
    }

    // ------------------------------------------------------------------------------------------ work queue

    /** Whether the payment has external work due at {@code now}. */
    public boolean isDue(Instant now) {
        return nextAttemptAt != null && !nextAttemptAt.isAfter(now);
    }

    /**
     * Takes the work for a worker: it is not due again before {@code until}. A worker whose process dies leaves the
     * lease to expire, after which another one retries.
     *
     * @throws IllegalStateException there is no work
     */
    public void leaseUntil(Instant until) {
        Objects.requireNonNull(until, "until");
        if (nextAttemptAt == null) {
            throw new IllegalStateException("Payment " + id + " has no work to lease");
        }
        nextAttemptAt = until;
    }

    /**
     * An attempt at the due work failed (a transient error). Counts it and, unless the policy is used up, schedules the
     * next one with exponential backoff and jitter.
     *
     * @return {@link RetryDecision#EXHAUSTED} after {@code maxAttempts} failures: the work is cleared and the caller
     *     decides what the payment becomes (for initiation: {@link #markInitiationFailed})
     */
    public RetryDecision scheduleRetry(
            Instant now, RetryPolicy policy, RandomGenerator random, String errorCode, String errorMessage) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(random, "random");
        attempts++;
        lastErrorCode = errorCode;
        lastErrorMessage = truncate(errorMessage);
        updatedAt = now;
        if (policy.isExhaustedAfter(attempts)) {
            nextAttemptAt = null;
            return RetryDecision.EXHAUSTED;
        }
        nextAttemptAt = policy.nextAttemptAfter(attempts, now, random);
        return RetryDecision.RETRY_SCHEDULED;
    }

    private void clearWork() {
        nextAttemptAt = null;
        attempts = 0;
    }

    // ------------------------------------------------------------------------------------------ internals

    private void requireStatus(PaymentStatus expected, String action) {
        if (status != expected) {
            throw new IllegalPaymentTransitionException(id, status, action);
        }
    }

    private void moveTo(PaymentStatus target, PaymentStatusSource source, String stripeEventId, Instant at) {
        history.add(new PaymentStatusChange(status, target, source, stripeEventId, at));
        status = target;
        updatedAt = at;
        // Work belongs to a status: initiation to CREATED, cancellation to the cancelable ones. Anywhere else it is
        // moot.
        if (target != PaymentStatus.REQUIRES_PAYMENT_METHOD && target != PaymentStatus.REQUIRES_ACTION) {
            clearWork();
        } else if (!cancelRequested || cancelSentAt != null) {
            clearWork();
        }
    }

    private static String truncate(String message) {
        return message == null || message.length() <= MAX_ERROR_MESSAGE_LENGTH
                ? message
                : message.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }

    // ------------------------------------------------------------------------------------------ state

    public UUID id() {
        return id;
    }

    public UUID orderId() {
        return orderId;
    }

    public String customerId() {
        return customerId;
    }

    public Money amount() {
        return amount;
    }

    public PaymentStatus status() {
        return status;
    }

    /** The Stripe PaymentIntent id; {@code null} until the PaymentIntent has been created. */
    public String stripePaymentIntentId() {
        return stripePaymentIntentId;
    }

    /** The {@code observedAt} of the latest applied Stripe report: the ordering watermark. */
    public Instant lastStripeEventAt() {
        return lastStripeEventAt;
    }

    public String lastErrorCode() {
        return lastErrorCode;
    }

    public String lastErrorMessage() {
        return lastErrorMessage;
    }

    public boolean cancelRequested() {
        return cancelRequested;
    }

    public Instant cancelSentAt() {
        return cancelSentAt;
    }

    public boolean disputed() {
        return disputed;
    }

    /** Failed attempts of the current work item. */
    public int attempts() {
        return attempts;
    }

    /** When the current work is due; {@code null} if there is none. */
    public Instant nextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    /** The optimistic-locking version; {@code null} until the payment has been stored. */
    public Long version() {
        return version;
    }

    /** The business flow this payment belongs to (from the {@code OrderCreated} that started it); may be {@code null}. */
    public UUID correlationId() {
        return correlationId;
    }

    /** The event that created this payment ({@code OrderCreated}); may be {@code null}. */
    public UUID causedByEventId() {
        return causedByEventId;
    }

    /** Returns the events registered since the last call and clears them. */
    public List<PaymentDomainEvent> pullDomainEvents() {
        List<PaymentDomainEvent> events = List.copyOf(domainEvents);
        domainEvents.clear();
        return events;
    }

    /** Oldest first. */
    public List<PaymentStatusChange> history() {
        return List.copyOf(history);
    }
}
