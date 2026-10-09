package com.altronixsoft.opp.payment.application;

import com.altronixsoft.opp.payment.domain.Payment;
import com.altronixsoft.opp.payment.domain.PaymentStatus;
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
 * Use case: the {@code PaymentCancellationWorker} (architecture §6.4, §7.6, ADR-0008). Cancels the PaymentIntent of
 * payments whose order was cancelled ({@code cancel_requested}) while they wait for a payment method or an action.
 *
 * <p>Same three phases as the initiation worker: claim and lease (transaction), call Stripe with
 * {@code pi-cancel:{paymentId}} (no transaction), record (new transaction). Locally only {@code cancel_sent_at} is
 * recorded: the status becomes {@code CANCELED} when {@code payment_intent.canceled} arrives, and the webhook publishes
 * {@code PaymentCanceled}. A payment still {@code CREATED} never gets here: it is cancelled on the spot when the order
 * event is consumed.
 *
 * <h2>The race with a success (F19)</h2>
 *
 * If the customer paid in the meantime, Stripe answers {@code payment_intent_unexpected_state}. That is final, not
 * retried: the {@code succeeded} webhook follows, payment-service publishes {@code PaymentSucceeded}, and order-service
 * refunds the cancelled order (F18).
 */
public class CancelPaymentIntentsService {

    static final String UNEXPECTED_STATE = "payment_intent_unexpected_state";

    private static final Logger log = LoggerFactory.getLogger(CancelPaymentIntentsService.class);
    private static final int MAX_RECORD_ATTEMPTS = 3;

    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final RandomGenerator random;
    private final WorkerSettings settings;

    public CancelPaymentIntentsService(
            PaymentRepository payments,
            PaymentGateway gateway,
            TransactionOperations transactions,
            Clock clock,
            RandomGenerator random,
            WorkerSettings settings) {
        this.payments = payments;
        this.gateway = gateway;
        this.transactions = transactions;
        this.clock = clock;
        this.random = random;
        this.settings = settings;
    }

    /** Processes one batch of due cancellations; an empty result means nothing was due. */
    public WorkBatchResult<CancellationOutcome> runBatch() {
        WorkBatchResult.Builder<CancellationOutcome> result = new WorkBatchResult.Builder<>(CancellationOutcome.class);
        for (Payment payment : claim()) {
            result.add(cancelSafely(payment));
        }
        return result.build();
    }

    private List<Payment> claim() {
        List<Payment> claimed = transactions.execute(status -> {
            Instant now = now();
            List<Payment> leased = new ArrayList<>();
            for (PaymentStatus cancelable :
                    List.of(PaymentStatus.REQUIRES_PAYMENT_METHOD, PaymentStatus.REQUIRES_ACTION)) {
                int room = settings.batchSize() - leased.size();
                if (room <= 0) {
                    break;
                }
                for (Payment payment : payments.claimDueBatch(cancelable, now, room)) {
                    if (hasCancelWork(payment)) {
                        payment.leaseUntil(now.plus(settings.lease()));
                        leased.add(payments.save(payment));
                    }
                }
            }
            return leased;
        });
        return claimed == null ? List.of() : claimed;
    }

    private static boolean hasCancelWork(Payment payment) {
        return payment.cancelRequested()
                && payment.cancelSentAt() == null
                && payment.nextAttemptAt() != null
                && payment.status().isCancelableAtStripe();
    }

    private CancellationOutcome cancelSafely(Payment payment) {
        try {
            return cancel(payment);
        } catch (PaymentConcurrentlyModifiedException e) {
            log.warn(
                    "Payment {} changed while its cancellation was being recorded; it will be picked up again",
                    payment.id());
            return CancellationOutcome.CONFLICT;
        } catch (RuntimeException e) {
            log.error("Cancelling payment {} failed unexpectedly; it will be picked up again", payment.id(), e);
            return CancellationOutcome.ERROR;
        }
    }

    private CancellationOutcome cancel(Payment payment) {
        // Stripe may have reported a final status since the claim: nothing to cancel any more.
        Optional<Payment> latest = payments.findById(payment.id());
        if (latest.isEmpty() || !hasCancelWork(latest.get())) {
            return CancellationOutcome.SKIPPED;
        }
        try {
            // Not inside a transaction: this is a network call (ADR-0008).
            gateway.cancelPaymentIntent(new CancelPaymentIntentRequest(
                    payment.id(), payment.stripePaymentIntentId(), CancelPaymentIntentRequest.Reason.ABANDONED));
        } catch (PaymentGatewayException failure) {
            return onFailure(payment, failure);
        }
        log.info(
                "Cancellation of PaymentIntent {} (payment {}) sent; waiting for the webhook",
                payment.stripePaymentIntentId(),
                payment.id());
        return record(payment.id(), current -> {
            if (current.cancelSentAt() != null) {
                return CancellationOutcome.SKIPPED;
            }
            current.markCancelSent(now());
            return CancellationOutcome.CANCEL_SENT;
        });
    }

    private CancellationOutcome onFailure(Payment payment, PaymentGatewayException failure) {
        String code = failure.code() == null ? "stripe_error" : failure.code();
        return switch (failure.errorClass()) {
            case TRANSIENT ->
                failure.circuitOpen()
                        ? defer(payment.id(), code, CancellationOutcome.DEFERRED)
                        : record(payment.id(), current -> retryLater(current, failure, code));
            case PERMANENT -> {
                if (UNEXPECTED_STATE.equals(code)) {
                    log.info(
                            "PaymentIntent {} of payment {} can no longer be cancelled (already processing or "
                                    + "succeeded); waiting for its outcome. A success is refunded by order-service (F19).",
                            payment.stripePaymentIntentId(),
                            payment.id());
                    yield giveUp(payment.id(), CancellationOutcome.TOO_LATE);
                }
                log.warn(
                        "Stripe refused to cancel PaymentIntent {} of payment {}: {} ({}); not retried",
                        payment.stripePaymentIntentId(),
                        payment.id(),
                        code,
                        failure.getMessage());
                yield giveUp(payment.id(), CancellationOutcome.GAVE_UP);
            }
            case CONFIG, IDEMPOTENCY_MISMATCH -> {
                log.error(
                        "Payment {} cannot be cancelled: {} ({}), request-id {}. Fix the configuration; it waits.",
                        payment.id(),
                        failure.errorClass(),
                        code,
                        failure.providerRequestId());
                yield defer(payment.id(), code, CancellationOutcome.DEFERRED);
            }
        };
    }

    private CancellationOutcome retryLater(Payment current, PaymentGatewayException failure, String code) {
        if (!hasCancelWork(current)) {
            return CancellationOutcome.SKIPPED;
        }
        RetryDecision decision =
                current.scheduleRetry(now(), settings.retryPolicy(), random, code, failure.getMessage());
        if (decision == RetryDecision.EXHAUSTED) {
            log.error(
                    "Giving up cancelling PaymentIntent {} of payment {} after {} attempts ({}); Stripe's final report "
                            + "decides",
                    current.stripePaymentIntentId(),
                    current.id(),
                    current.attempts(),
                    code);
            current.giveUpCancel(now());
            return CancellationOutcome.GAVE_UP;
        }
        return CancellationOutcome.RETRY_SCHEDULED;
    }

    private CancellationOutcome giveUp(UUID paymentId, CancellationOutcome outcome) {
        return record(paymentId, current -> {
            if (!hasCancelWork(current)) {
                return CancellationOutcome.SKIPPED;
            }
            current.giveUpCancel(now());
            return outcome;
        });
    }

    /** Waits {@code settings.deferral()} without counting an attempt. */
    private CancellationOutcome defer(UUID paymentId, String code, CancellationOutcome outcome) {
        return record(paymentId, current -> {
            if (!hasCancelWork(current)) {
                return CancellationOutcome.SKIPPED;
            }
            current.leaseUntil(now().plus(settings.deferral()));
            log.debug("Cancellation of payment {} deferred ({})", paymentId, code);
            return outcome;
        });
    }

    /** Runs {@code change} on the freshly loaded payment in a new transaction; a lost race reloads and repeats. */
    private CancellationOutcome record(UUID paymentId, Function<Payment, CancellationOutcome> change) {
        PaymentConcurrentlyModifiedException lost = null;
        for (int attempt = 1; attempt <= MAX_RECORD_ATTEMPTS; attempt++) {
            try {
                CancellationOutcome outcome = transactions.execute(status -> {
                    Payment current = payments.findById(paymentId)
                            .orElseThrow(() -> new IllegalStateException("Payment " + paymentId + " has disappeared"));
                    CancellationOutcome result = change.apply(current);
                    if (result != CancellationOutcome.SKIPPED) {
                        payments.save(current);
                    }
                    return result;
                });
                return outcome == null ? CancellationOutcome.ERROR : outcome;
            } catch (PaymentConcurrentlyModifiedException e) {
                lost = e;
            }
        }
        throw lost;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
