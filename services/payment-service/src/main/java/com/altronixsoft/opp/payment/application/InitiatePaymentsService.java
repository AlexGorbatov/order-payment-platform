package com.altronixsoft.opp.payment.application;

import com.altronixsoft.opp.payment.domain.Payment;
import com.altronixsoft.opp.payment.domain.PaymentDomainException;
import com.altronixsoft.opp.payment.domain.PaymentStatus;
import com.altronixsoft.opp.payment.domain.PaymentStatusSource;
import com.altronixsoft.opp.payment.domain.RetryDecision;
import com.altronixsoft.opp.payment.domain.StripePaymentIntentStatus;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Use case: the {@code PaymentInitiationWorker} (architecture §6.1, §7.6, ADR-0008). Creates the PaymentIntent of payments
 * that wait in {@code CREATED}.
 *
 * <p>One run has three phases, and the Stripe call is deliberately <b>not</b> inside any transaction:
 *
 * <ol>
 *   <li><b>Claim</b> (transaction): the due {@code CREATED} payments are locked with {@code FOR UPDATE SKIP LOCKED} and
 *       leased: {@code next_attempt_at} moves {@code lease} into the future and the transaction commits. Other workers do
 *       not see the payments any more, and a worker that dies leaves a lease that simply expires.
 *   <li><b>Call</b> (no transaction): {@link PaymentGateway#createPaymentIntent} with the idempotency key
 *       {@code pi-create:{paymentId}}. If the call times out, or succeeds at Stripe but its answer is lost, the payment is
 *       still {@code CREATED} and is claimed again after the lease; the repeated call carries the same key, so Stripe
 *       answers with the PaymentIntent it already made (F04, F05).
 *   <li><b>Record</b> (new transaction): the payment is reloaded, updated and saved together with its outbox event
 *       ({@code PaymentInitiated} / {@code PaymentInitiationFailed}). A crash or an exception here rolls everything back;
 *       the payment stays {@code CREATED} and phase 2 repeats safely.
 * </ol>
 *
 * <h2>Why a payment is failed after 23 hours (F08)</h2>
 *
 * Stripe remembers an idempotency key for <em>at least</em> 24 hours and then forgets it. A retry with a forgotten key is a
 * new request: it would create a <em>second</em> PaymentIntent for the same order, and the customer could end up paying
 * twice. So a payment that is still {@code CREATED} when it is {@code idempotencyWindow} (23 h, an hour of margin) old is
 * moved to {@code INITIATION_FAILED} and the order is told, instead of being retried. The window counts from the creation
 * of the payment, which is never later than the first use of the key.
 *
 * <p>Every payment is processed on its own: a failure with one never stops the others.
 */
@Service
public class InitiatePaymentsService {

    private static final Logger log = LoggerFactory.getLogger(InitiatePaymentsService.class);

    /** Reloading and saving again after losing an optimistic-lock race is cheap; more than this is a livelock. */
    private static final int MAX_RECORD_ATTEMPTS = 3;

    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final PaymentEventPublisher events;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final RandomGenerator random;
    private final InitiationSettings settings;

    public InitiatePaymentsService(
            PaymentRepository payments,
            PaymentGateway gateway,
            PaymentEventPublisher events,
            TransactionOperations transactions,
            Clock clock,
            RandomGenerator random,
            InitiationSettings settings) {
        this.payments = payments;
        this.gateway = gateway;
        this.events = events;
        this.transactions = transactions;
        this.clock = clock;
        this.random = random;
        this.settings = settings;
    }

    /**
     * Processes one batch of due payments.
     *
     * @return what happened to each claimed payment; an empty result means nothing was due
     */
    public InitiationBatchResult runBatch() {
        List<Payment> claimed = claim();
        InitiationBatchResult.Builder result = new InitiationBatchResult.Builder();
        for (Payment payment : claimed) {
            result.add(initiateSafely(payment));
        }
        return result.build();
    }

    // ------------------------------------------------------------------------------------------ phase 1: claim

    private List<Payment> claim() {
        List<Payment> claimed = transactions.execute(status -> {
            Instant now = now();
            List<Payment> leased = new ArrayList<>();
            for (Payment payment : payments.claimDueBatch(PaymentStatus.CREATED, now, settings.batchSize())) {
                payment.leaseUntil(now.plus(settings.lease()));
                leased.add(payments.save(payment));
            }
            return leased;
        });
        return claimed == null ? List.of() : claimed;
    }

    // ------------------------------------------------------------------------------------------ phase 2: call

    private InitiationOutcome initiateSafely(Payment payment) {
        try {
            return initiate(payment);
        } catch (PaymentConcurrentlyModifiedException e) {
            log.warn("Payment {} changed while it was being initiated; it will be picked up again", payment.id());
            return InitiationOutcome.CONFLICT;
        } catch (RuntimeException e) {
            log.error("Initiating payment {} failed unexpectedly; it will be picked up again", payment.id(), e);
            return InitiationOutcome.ERROR;
        }
    }

    private InitiationOutcome initiate(Payment payment) {
        Instant now = now();
        if (payment.createdAt().plus(settings.idempotencyWindow()).isBefore(now)) {
            log.warn(
                    "Payment {} has waited in CREATED since {}, longer than the idempotency window {}; failing it "
                            + "instead of retrying with a key Stripe may have forgotten",
                    payment.id(),
                    payment.createdAt(),
                    settings.idempotencyWindow());
            return record(
                    payment.id(),
                    current -> failInitiation(
                            current,
                            "idempotency_window_elapsed",
                            "Not initiated within the idempotency window",
                            InitiationOutcome.FAILED_WINDOW_ELAPSED));
        }

        // A cancellation may have arrived since the claim: do not create a PaymentIntent nobody will ever pay.
        Optional<Payment> latest = payments.findById(payment.id());
        if (latest.isEmpty() || latest.get().status() != PaymentStatus.CREATED) {
            log.info("Payment {} is no longer CREATED; not creating a PaymentIntent", payment.id());
            return InitiationOutcome.SKIPPED_NO_LONGER_CREATED;
        }

        GatewayPaymentIntent intent;
        try {
            // Not inside a transaction: this is a network call (ADR-0008).
            intent = gateway.createPaymentIntent(
                    new CreatePaymentIntentRequest(payment.id(), payment.orderId(), payment.amount()));
        } catch (PaymentGatewayException failure) {
            return onGatewayFailure(payment, failure);
        }
        return onPaymentIntentCreated(payment, intent);
    }

    // ------------------------------------------------------------------------------------------ phase 3: record

    private InitiationOutcome onPaymentIntentCreated(Payment payment, GatewayPaymentIntent intent) {
        try {
            // fail before touching the payment if Stripe reports a state this platform does not use (requires_capture)
            StripePaymentIntentStatus.toPaymentStatus(intent.status());
        } catch (PaymentDomainException e) {
            log.error(
                    "Payment {}: PaymentIntent {} has an unusable status {}: {}",
                    payment.id(),
                    intent.id(),
                    intent.status(),
                    e.getMessage());
            return defer(
                    payment, "unsupported_payment_intent_status", e.getMessage(), InitiationOutcome.DEFERRED_CONFIG);
        }
        InitiationOutcome outcome = record(payment.id(), current -> attach(current, intent));
        if (outcome == InitiationOutcome.SKIPPED_NO_LONGER_CREATED) {
            cancelOrphan(payment, intent);
        }
        return outcome;
    }

    private InitiationOutcome attach(Payment current, GatewayPaymentIntent intent) {
        if (current.status() != PaymentStatus.CREATED) {
            log.warn(
                    "Payment {} is {} and cannot take PaymentIntent {} any more (cancelled while Stripe was called)",
                    current.id(),
                    current.status(),
                    intent.id());
            return InitiationOutcome.SKIPPED_NO_LONGER_CREATED;
        }
        current.attachPaymentIntent(intent.id(), intent.created());
        if (StripePaymentIntentStatus.toPaymentStatus(intent.status()) != PaymentStatus.REQUIRES_PAYMENT_METHOD) {
            // a replayed answer can be newer than "just created": take the state Stripe reports now
            Instant observedAt = now().isAfter(intent.created()) ? now() : intent.created();
            current.applyStripeStatus(intent.status(), observedAt, PaymentStatusSource.STRIPE_API);
        }
        return InitiationOutcome.INITIATED;
    }

    /**
     * Best effort: the payment was cancelled while its PaymentIntent was being created, so nobody should ever pay it. It
     * is cancelled at Stripe so that it does not linger; if that fails the intent is harmless (nobody has its client
     * secret) and is left alone.
     */
    private void cancelOrphan(Payment payment, GatewayPaymentIntent intent) {
        try {
            gateway.cancelPaymentIntent(new CancelPaymentIntentRequest(
                    payment.id(), intent.id(), CancelPaymentIntentRequest.Reason.ABANDONED));
        } catch (PaymentGatewayException e) {
            log.warn(
                    "Could not cancel the orphaned PaymentIntent {} of payment {}: {}",
                    intent.id(),
                    payment.id(),
                    e.getMessage());
        }
    }

    private InitiationOutcome onGatewayFailure(Payment payment, PaymentGatewayException failure) {
        String code = failure.code() == null ? "stripe_error" : failure.code();
        return switch (failure.errorClass()) {
            case TRANSIENT ->
                failure.circuitOpen()
                        ? defer(payment, code, failure.getMessage(), InitiationOutcome.DEFERRED_CIRCUIT_OPEN)
                        : record(payment.id(), current -> retryLater(current, failure, code));
            case PERMANENT ->
                record(
                        payment.id(),
                        current -> failInitiation(
                                current, code, failure.getMessage(), InitiationOutcome.FAILED_PERMANENT));
            case CONFIG, IDEMPOTENCY_MISMATCH -> {
                // Not the payment's fault, and retrying at full speed would only repeat it: alert, wait, keep the
                // attempts.
                log.error(
                        "Payment {} cannot be initiated: {} ({}), request-id {}. Fix the configuration; the payment waits.",
                        payment.id(),
                        failure.errorClass(),
                        code,
                        failure.providerRequestId());
                yield defer(payment, code, failure.getMessage(), InitiationOutcome.DEFERRED_CONFIG);
            }
        };
    }

    private InitiationOutcome retryLater(Payment current, PaymentGatewayException failure, String code) {
        if (current.status() != PaymentStatus.CREATED) {
            return InitiationOutcome.SKIPPED_NO_LONGER_CREATED;
        }
        RetryDecision decision =
                current.scheduleRetry(now(), settings.retryPolicy(), random, code, failure.getMessage());
        if (decision == RetryDecision.EXHAUSTED) {
            return failInitiation(
                    current, "retries_exhausted", failure.getMessage(), InitiationOutcome.FAILED_EXHAUSTED);
        }
        log.info(
                "Payment {}: transient failure {} (attempt {}), next attempt at {}",
                current.id(),
                code,
                current.attempts(),
                current.nextAttemptAt());
        return InitiationOutcome.RETRY_SCHEDULED;
    }

    /** Waits {@code settings.deferral()} without counting an attempt. */
    private InitiationOutcome defer(Payment payment, String code, String message, InitiationOutcome outcome) {
        return record(payment.id(), current -> {
            if (current.status() != PaymentStatus.CREATED) {
                return InitiationOutcome.SKIPPED_NO_LONGER_CREATED;
            }
            Instant now = now();
            current.recordError(code, message, now);
            current.leaseUntil(now.plus(settings.deferral()));
            return outcome;
        });
    }

    /** Fails the initiation if the payment is still {@code CREATED}; otherwise there is nothing left to fail. */
    private InitiationOutcome failInitiation(Payment current, String code, String message, InitiationOutcome outcome) {
        if (current.status() != PaymentStatus.CREATED) {
            return InitiationOutcome.SKIPPED_NO_LONGER_CREATED;
        }
        current.markInitiationFailed(code, message, now());
        return outcome;
    }

    /**
     * Runs {@code change} on the freshly loaded payment in a new transaction and saves it with the events it registered.
     * A lost optimistic-lock race reloads and repeats, because the winner may have cancelled the payment.
     */
    private InitiationOutcome record(UUID paymentId, Function<Payment, InitiationOutcome> change) {
        PaymentConcurrentlyModifiedException lost = null;
        for (int attempt = 1; attempt <= MAX_RECORD_ATTEMPTS; attempt++) {
            try {
                InitiationOutcome outcome = transactions.execute(status -> {
                    Payment current = payments.findById(paymentId)
                            .orElseThrow(() -> new IllegalStateException("Payment " + paymentId + " has disappeared"));
                    InitiationOutcome result = change.apply(current);
                    if (result != InitiationOutcome.SKIPPED_NO_LONGER_CREATED) {
                        payments.save(current);
                        events.publish(current.pullDomainEvents(), current.correlationId(), current.causedByEventId());
                    }
                    return result;
                });
                return Optional.ofNullable(outcome).orElse(InitiationOutcome.ERROR);
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
