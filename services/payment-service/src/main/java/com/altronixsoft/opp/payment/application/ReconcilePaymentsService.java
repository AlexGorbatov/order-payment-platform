package com.altronixsoft.opp.payment.application;

import com.altronixsoft.opp.payment.domain.Payment;
import com.altronixsoft.opp.payment.domain.PaymentDomainEvent;
import com.altronixsoft.opp.payment.domain.PaymentStatus;
import com.altronixsoft.opp.payment.domain.PaymentStatusSource;
import com.altronixsoft.opp.payment.domain.StripeOutcome;
import com.altronixsoft.opp.payment.domain.StripePaymentIntentStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Use case: the {@code ReconciliationJob} (architecture §8.4, ADR-0010): the safety net for lost or late webhooks
 * (F11).
 *
 * <ol>
 *   <li><b>Claim</b> (transaction): payments in {@code REQUIRES_PAYMENT_METHOD}, {@code REQUIRES_ACTION} or
 *       {@code PROCESSING} with a PaymentIntent, unchanged for {@code staleAfter} and not checked within it, are
 *       locked with {@code FOR UPDATE SKIP LOCKED} and marked as being checked ({@code last_reconciled_at}), so that
 *       several instances never check the same payment and a checked payment rests for {@code staleAfter}.
 *   <li><b>Ask</b> (no transaction, rate-limited): {@code retrievePaymentIntent}.
 *   <li><b>Apply</b> (new transaction): Stripe's status goes through the same state machine and ordering rule as a
 *       webhook ({@code applyStripeStatus}, source {@code RECONCILIATION}), with {@code observedAt} = the second the
 *       request started (ADR-0010). A changed status is a <b>drift</b>: history and outbox events exactly as for the
 *       webhook that was missed, plus a WARN. A webhook that changes the payment at the same moment wins or loses the
 *       optimistic lock; the loser reloads and finds the change made — one transition, one event (F21).
 * </ol>
 *
 * A payment that cannot be checked (Stripe unavailable) is released and checked again by the next run; the run itself
 * never fails because of one payment. The latest summary is kept in memory for {@code GET /admin/reconciliation/last}.
 */
public class ReconcilePaymentsService {

    private static final Logger log = LoggerFactory.getLogger(ReconcilePaymentsService.class);
    private static final int MAX_APPLY_ATTEMPTS = 3;
    private static final List<PaymentStatus> CHECKED_STATUSES =
            List.of(PaymentStatus.REQUIRES_PAYMENT_METHOD, PaymentStatus.REQUIRES_ACTION, PaymentStatus.PROCESSING);

    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final PaymentEventPublisher events;
    private final StripeCallThrottle throttle;
    private final ReconciliationRecorder recorder;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final ReconciliationSettings settings;
    private final AtomicReference<ReconciliationSummary> last = new AtomicReference<>();

    public ReconcilePaymentsService(
            PaymentRepository payments,
            PaymentGateway gateway,
            PaymentEventPublisher events,
            StripeCallThrottle throttle,
            ReconciliationRecorder recorder,
            TransactionOperations transactions,
            Clock clock,
            ReconciliationSettings settings) {
        this.payments = payments;
        this.gateway = gateway;
        this.events = events;
        this.throttle = throttle;
        this.recorder = recorder;
        this.transactions = transactions;
        this.clock = clock;
        this.settings = settings;
    }

    /** One run; never throws because of a single payment. */
    public ReconciliationSummary run(ReconciliationSummary.Trigger trigger) {
        Instant startedAt = now();
        ReconciliationSummary.Builder summary = new ReconciliationSummary.Builder(trigger, startedAt);
        List<UUID> claimed = claim(startedAt);
        for (int i = 0; i < claimed.size(); i++) {
            UUID paymentId = claimed.get(i);
            if (!throttle.tryAcquire()) {
                // Stripe's budget for background work is used up: the rest waits for the next run.
                for (UUID rest : claimed.subList(i, claimed.size())) {
                    release(rest);
                    summary.deferred();
                }
                log.info("Reconciliation rate limit reached; {} payment(s) left for the next run", claimed.size() - i);
                break;
            }
            check(paymentId, summary);
        }
        ReconciliationSummary result = summary.build(now());
        last.set(result);
        recorder.record(result);
        if (result.checked() > 0 || result.failed() > 0 || result.deferred() > 0) {
            log.info(
                    "Reconciliation ({}): checked {}, drifted {}, unchanged {}, failed {}, deferred {}",
                    trigger,
                    result.checked(),
                    result.drifted(),
                    result.unchanged(),
                    result.failed(),
                    result.deferred());
        }
        return result;
    }

    /** The summary of the latest run since the start of this instance. */
    public Optional<ReconciliationSummary> lastRun() {
        return Optional.ofNullable(last.get());
    }

    private List<UUID> claim(Instant now) {
        List<UUID> claimed = transactions.execute(
                status -> payments.claimForReconciliation(now.minus(settings.staleAfter()), now, settings.batchSize()));
        return claimed == null ? List.of() : claimed;
    }

    private void check(UUID paymentId, ReconciliationSummary.Builder summary) {
        Optional<Payment> found = payments.findById(paymentId);
        if (found.isEmpty() || !isReconcilable(found.get())) {
            return;
        }
        Payment payment = found.get();
        // The PaymentIntent is at least as new as the moment the request starts. Stripe's own timestamps have whole
        // seconds, so the watermark is that second: a webhook of the same second still competes on the transition rule.
        Instant observedAt = now().truncatedTo(ChronoUnit.SECONDS);
        GatewayPaymentIntent intent;
        try {
            // Not inside a transaction: this is a network call (ADR-0008).
            intent = gateway.retrievePaymentIntent(payment.stripePaymentIntentId());
        } catch (RuntimeException e) {
            log.warn(
                    "Reconciliation could not retrieve PaymentIntent {} of payment {}: {}; it is checked again next run",
                    payment.stripePaymentIntentId(),
                    payment.id(),
                    e.getMessage());
            release(paymentId);
            summary.failed();
            return;
        }
        summary.checked();
        try {
            apply(paymentId, intent, observedAt, summary);
        } catch (RuntimeException e) {
            log.error("Reconciliation could not apply Stripe's status {} to payment {}", intent.status(), paymentId, e);
            release(paymentId);
            summary.failed();
        }
    }

    private void apply(
            UUID paymentId, GatewayPaymentIntent intent, Instant observedAt, ReconciliationSummary.Builder summary) {
        PaymentConcurrentlyModifiedException lost = null;
        for (int attempt = 1; attempt <= MAX_APPLY_ATTEMPTS; attempt++) {
            try {
                transactions.execute(status -> {
                    applyOnce(paymentId, intent, observedAt, summary);
                    return null;
                });
                return;
            } catch (PaymentConcurrentlyModifiedException e) {
                // A webhook changed the payment meanwhile (F21): reload; the state machine sees what it did.
                lost = e;
            }
        }
        throw lost;
    }

    private void applyOnce(
            UUID paymentId, GatewayPaymentIntent intent, Instant observedAt, ReconciliationSummary.Builder summary) {
        Payment current = payments.findById(paymentId)
                .orElseThrow(() -> new IllegalStateException("Payment " + paymentId + " has disappeared"));
        PaymentStatus from = current.status();
        PaymentStatus target = StripePaymentIntentStatus.toPaymentStatus(intent.status());
        StripeOutcome outcome = target == PaymentStatus.REQUIRES_PAYMENT_METHOD
                        && from != PaymentStatus.REQUIRES_PAYMENT_METHOD
                        && intent.lastErrorCode() != null
                ? current.recordPaymentFailure(
                        intent.status(),
                        intent.lastErrorCode(),
                        intent.lastErrorDeclineCode(),
                        intent.lastErrorMessage(),
                        observedAt,
                        PaymentStatusSource.RECONCILIATION,
                        null)
                : current.applyStripeStatus(intent.status(), observedAt, PaymentStatusSource.RECONCILIATION, null);
        if (outcome != StripeOutcome.APPLIED) {
            // Stripe agrees, or reports something older than what a webhook told us meanwhile: nothing to write.
            summary.unchanged();
            return;
        }
        payments.save(current);
        List<PaymentDomainEvent> registered = current.pullDomainEvents();
        events.publish(registered, current.correlationId(), current.causedByEventId());
        summary.drift(paymentId, from, current.status());
        log.warn(
                "Reconciliation drift: payment {} (order {}) was {}, Stripe says {} — a webhook was lost or late",
                paymentId,
                current.orderId(),
                from,
                current.status());
    }

    private static boolean isReconcilable(Payment payment) {
        return payment.stripePaymentIntentId() != null && CHECKED_STATUSES.contains(payment.status());
    }

    private void release(UUID paymentId) {
        try {
            transactions.execute(status -> {
                payments.releaseReconciliation(paymentId);
                return null;
            });
        } catch (RuntimeException e) {
            log.warn("Could not release payment {} for the next reconciliation run", paymentId, e);
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
