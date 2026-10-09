package com.altronixsoft.opp.payment.application;

import com.altronixsoft.opp.payment.domain.Payment;
import com.altronixsoft.opp.payment.domain.PaymentStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link PaymentRepository} in memory, for the unit tests of the use cases. Like a database it hands out <em>copies</em>
 * (a change is only stored by {@code save}), and it can be rolled back with the {@link FakeTransactions} that wrap it.
 */
class InMemoryPayments implements PaymentRepository, FakeTransactions.Rollbackable {

    private Map<UUID, Payment> stored = new LinkedHashMap<>();
    int saves;
    /** Runs once, right before the first read: something that happens after the claim has committed. */
    Runnable beforeFirstRead = () -> {};

    private boolean read;

    /** The stored aggregates themselves, for assertions and for tests that play "somebody else changed it". */
    Map<UUID, Payment> stored() {
        return stored;
    }

    Payment add(Payment payment) {
        stored.put(payment.id(), copyOf(payment));
        return payment;
    }

    Payment get(UUID id) {
        return stored.get(id);
    }

    static Payment copyOf(Payment p) {
        return Payment.restore(
                p.id(),
                p.orderId(),
                p.customerId(),
                p.amount(),
                p.status(),
                p.stripePaymentIntentId(),
                p.lastStripeEventAt(),
                p.lastErrorCode(),
                p.lastDeclineCode(),
                p.lastErrorMessage(),
                p.cancelRequested(),
                p.cancelSentAt(),
                p.disputed(),
                p.attempts(),
                p.nextAttemptAt(),
                p.createdAt(),
                p.updatedAt(),
                p.version(),
                p.correlationId(),
                p.causedByEventId(),
                p.history());
    }

    private void firstRead() {
        if (!read) {
            read = true;
            beforeFirstRead.run();
        }
    }

    @Override
    public Optional<Payment> findById(UUID id) {
        firstRead();
        return Optional.ofNullable(stored.get(id)).map(InMemoryPayments::copyOf);
    }

    @Override
    public Optional<Payment> findByOrderId(UUID orderId) {
        firstRead();
        return stored.values().stream()
                .filter(p -> p.orderId().equals(orderId))
                .findFirst()
                .map(InMemoryPayments::copyOf);
    }

    @Override
    public Optional<Payment> findByStripePaymentIntentId(String paymentIntentId) {
        return stored.values().stream()
                .filter(p -> paymentIntentId.equals(p.stripePaymentIntentId()))
                .findFirst()
                .map(InMemoryPayments::copyOf);
    }

    @Override
    public Payment save(Payment payment) {
        saves++;
        stored.put(payment.id(), copyOf(payment));
        return copyOf(payment);
    }

    @Override
    public List<Payment> claimDueBatch(PaymentStatus status, Instant now, int limit) {
        return stored.values().stream()
                .filter(p -> p.status() == status && p.isDue(now))
                .sorted(Comparator.comparing(Payment::nextAttemptAt).thenComparing(Payment::id))
                .limit(limit)
                .map(InMemoryPayments::copyOf)
                .toList();
    }

    @Override
    public Object snapshot() {
        Map<UUID, Payment> copy = new LinkedHashMap<>();
        stored.forEach((id, p) -> copy.put(id, copyOf(p)));
        return copy;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void rollbackTo(Object snapshot) {
        stored = new LinkedHashMap<>((Map<UUID, Payment>) snapshot);
    }

    /** All stored payments, oldest first. */
    List<Payment> all() {
        return new ArrayList<>(stored.values());
    }
}
