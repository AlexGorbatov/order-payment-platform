package com.altronixsoft.opp.payment.application;

import com.altronixsoft.opp.payment.domain.Payment;
import com.altronixsoft.opp.payment.domain.PaymentStatus;
import com.altronixsoft.opp.payment.domain.Refund;
import com.altronixsoft.opp.payment.domain.RefundStatus;
import com.altronixsoft.opp.payment.domain.RetryDecision;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Use case: the {@code RefundWorker} (architecture §6.5, §7.6, ADR-0008). Creates at Stripe the refunds that wait in
 * {@code REQUESTED}.
 *
 * <p>Claim and lease (transaction), {@code createRefund} with the idempotency key {@code refund:{refundId}} and
 * {@code metadata refundId / paymentId / orderId} (no transaction), record (new transaction): {@code PENDING} with the
 * Stripe refund id. The outcome arrives as a webhook: {@code charge.refunded} makes the refund {@code SUCCEEDED} and the
 * payment {@code REFUNDED} ({@code PaymentRefunded}); {@code refund.failed} makes it {@code FAILED}
 * ({@code PaymentRefundFailed}, F20).
 *
 * <p>A refund fails right here, with {@code PaymentRefundFailed}, when Stripe refuses it, when the transient retries are
 * used up, or when its payment is not {@code SUCCEEDED} (nothing to refund). The key makes a repeated call after a
 * timeout return the refund Stripe already made, so the money never goes back twice (F22 for the request itself is the
 * unique {@code refund_request_id}).
 */
public class CreateRefundsService {

    private static final Logger log = LoggerFactory.getLogger(CreateRefundsService.class);
    private static final int MAX_RECORD_ATTEMPTS = 3;

    private final RefundRepository refunds;
    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final PaymentEventPublisher events;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final RandomGenerator random;
    private final WorkerSettings settings;

    public CreateRefundsService(
            RefundRepository refunds,
            PaymentRepository payments,
            PaymentGateway gateway,
            PaymentEventPublisher events,
            TransactionOperations transactions,
            Clock clock,
            RandomGenerator random,
            WorkerSettings settings) {
        this.refunds = refunds;
        this.payments = payments;
        this.gateway = gateway;
        this.events = events;
        this.transactions = transactions;
        this.clock = clock;
        this.random = random;
        this.settings = settings;
    }

    /** Processes one batch of due refunds; an empty result means nothing was due. */
    public WorkBatchResult<RefundOutcome> runBatch() {
        WorkBatchResult.Builder<RefundOutcome> result = new WorkBatchResult.Builder<>(RefundOutcome.class);
        for (Refund refund : claim()) {
            result.add(createSafely(refund));
        }
        return result.build();
    }

    private List<Refund> claim() {
        List<Refund> claimed = transactions.execute(status -> {
            Instant now = now();
            List<Refund> leased = new ArrayList<>();
            for (Refund refund : refunds.claimDueBatch(RefundStatus.REQUESTED, now, settings.batchSize())) {
                refund.leaseUntil(now.plus(settings.lease()));
                leased.add(refunds.save(refund));
            }
            return leased;
        });
        return claimed == null ? List.of() : claimed;
    }

    private RefundOutcome createSafely(Refund refund) {
        try {
            return create(refund);
        } catch (RefundConcurrentlyModifiedException | PaymentConcurrentlyModifiedException e) {
            log.warn("Refund {} changed while it was being recorded; it will be picked up again", refund.id());
            return RefundOutcome.CONFLICT;
        } catch (RuntimeException e) {
            log.error("Creating refund {} failed unexpectedly; it will be picked up again", refund.id(), e);
            return RefundOutcome.ERROR;
        }
    }

    private RefundOutcome create(Refund refund) {
        Optional<Refund> latest = refunds.findById(refund.id());
        if (latest.isEmpty() || latest.get().status() != RefundStatus.REQUESTED) {
            return RefundOutcome.SKIPPED;
        }
        Payment payment = payments.findById(refund.paymentId())
                .orElseThrow(() -> new IllegalStateException("Refund " + refund.id() + " has no payment"));
        if (payment.status() != PaymentStatus.SUCCEEDED || payment.stripePaymentIntentId() == null) {
            log.warn(
                    "Refund {} cannot be made: payment {} is {}, not SUCCEEDED",
                    refund.id(),
                    payment.id(),
                    payment.status());
            return fail(refund.id(), "payment_not_refundable");
        }
        GatewayRefund created;
        try {
            // Not inside a transaction: this is a network call (ADR-0008).
            created = gateway.createRefund(new CreateRefundRequest(
                    refund.id(), payment.id(), payment.orderId(), payment.stripePaymentIntentId(), refund.amount()));
        } catch (PaymentGatewayException failure) {
            return onFailure(refund, failure);
        }
        if ("failed".equals(created.status()) || "canceled".equals(created.status())) {
            log.warn("Stripe created refund {} for refund {} already {}", created.id(), refund.id(), created.status());
            return fail(refund.id(), created.failureReason() == null ? created.status() : created.failureReason());
        }
        log.info(
                "Refund {} created at Stripe as {} ({}); waiting for the webhook",
                refund.id(),
                created.id(),
                created.status());
        return record(refund.id(), current -> {
            current.markPending(created.id(), now());
            return RefundOutcome.CREATED_AT_STRIPE;
        });
    }

    private RefundOutcome onFailure(Refund refund, PaymentGatewayException failure) {
        String code = failure.code() == null ? "stripe_error" : failure.code();
        return switch (failure.errorClass()) {
            case TRANSIENT ->
                failure.circuitOpen()
                        ? defer(refund.id(), RefundOutcome.DEFERRED)
                        : retryLater(refund.id(), failure, code);
            case PERMANENT -> {
                log.warn("Stripe refused refund {}: {} ({})", refund.id(), code, failure.getMessage());
                yield fail(refund.id(), code);
            }
            case CONFIG, IDEMPOTENCY_MISMATCH -> {
                log.error(
                        "Refund {} cannot be created: {} ({}), request-id {}. Fix the configuration; it waits.",
                        refund.id(),
                        failure.errorClass(),
                        code,
                        failure.providerRequestId());
                yield defer(refund.id(), RefundOutcome.DEFERRED);
            }
        };
    }

    private RefundOutcome retryLater(UUID refundId, PaymentGatewayException failure, String code) {
        RefundOutcome outcome = record(refundId, current -> {
            RetryDecision decision = current.scheduleRetry(now(), settings.retryPolicy(), random, failure.getMessage());
            return decision == RetryDecision.EXHAUSTED ? RefundOutcome.FAILED : RefundOutcome.RETRY_SCHEDULED;
        });
        if (outcome == RefundOutcome.FAILED) {
            log.error("Giving up refund {} after its retries ({})", refundId, code);
            return fail(refundId, "retries_exhausted");
        }
        return outcome;
    }

    /** Waits {@code settings.deferral()} without counting an attempt. */
    private RefundOutcome defer(UUID refundId, RefundOutcome outcome) {
        return record(refundId, current -> {
            current.leaseUntil(now().plus(settings.deferral()));
            return outcome;
        });
    }

    /**
     * Fails the refund and tells the order: {@code FAILED} and, on the payment (unchanged otherwise),
     * {@code PaymentRefundFailed} — in one transaction.
     */
    private RefundOutcome fail(UUID refundId, String reason) {
        PaymentConcurrentlyModifiedException lost = null;
        for (int attempt = 1; attempt <= MAX_RECORD_ATTEMPTS; attempt++) {
            try {
                RefundOutcome outcome = transactions.execute(status -> {
                    Refund current = refunds.findById(refundId)
                            .orElseThrow(() -> new IllegalStateException("Refund " + refundId + " has disappeared"));
                    if (current.status() != RefundStatus.REQUESTED) {
                        return RefundOutcome.SKIPPED;
                    }
                    Instant now = now();
                    current.markFailed(reason, now);
                    refunds.save(current);
                    Payment payment = payments.findById(current.paymentId())
                            .orElseThrow(() -> new IllegalStateException("Refund " + refundId + " has no payment"));
                    payment.recordRefundFailure(current.refundRequestId(), reason, now);
                    payments.save(payment);
                    events.publish(payment.pullDomainEvents(), current.correlationId(), current.causedByEventId());
                    return RefundOutcome.FAILED;
                });
                return outcome == null ? RefundOutcome.ERROR : outcome;
            } catch (PaymentConcurrentlyModifiedException e) {
                lost = e;
            }
        }
        throw lost;
    }

    /** Runs {@code change} on the freshly loaded refund while it is still {@code REQUESTED}, in a new transaction. */
    private RefundOutcome record(UUID refundId, Function<Refund, RefundOutcome> change) {
        RefundOutcome outcome = transactions.execute(status -> {
            Refund current = refunds.findById(refundId)
                    .orElseThrow(() -> new IllegalStateException("Refund " + refundId + " has disappeared"));
            if (current.status() != RefundStatus.REQUESTED) {
                return RefundOutcome.SKIPPED;
            }
            RefundOutcome result = change.apply(current);
            refunds.save(current);
            return result;
        });
        return outcome == null ? RefundOutcome.ERROR : outcome;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
