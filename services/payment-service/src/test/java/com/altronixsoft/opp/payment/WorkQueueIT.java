package com.altronixsoft.opp.payment;

import static com.altronixsoft.opp.payment.domain.PaymentFixtures.eur;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.altronixsoft.opp.payment.domain.Payment;
import com.altronixsoft.opp.payment.domain.PaymentStatus;
import com.altronixsoft.opp.payment.domain.PaymentStatusSource;
import com.altronixsoft.opp.payment.domain.Refund;
import com.altronixsoft.opp.payment.domain.RefundStatus;
import com.altronixsoft.opp.payment.domain.StripeWebhookEvent;
import com.altronixsoft.opp.payment.domain.WebhookEventStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The work queues of architecture §7.6 on a real PostgreSQL: {@code claimDueBatch} locks with
 * {@code FOR UPDATE SKIP LOCKED}, so concurrent workers never get the same row, and a lease keeps a row claimed after
 * the claiming transaction has ended.
 */
class WorkQueueIT extends AbstractPersistenceIT {

    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");

    private static Instant at(int seconds) {
        return NOW.plusSeconds(seconds);
    }

    /** A payment due for PaymentIntent creation at {@code dueAt}. */
    private Payment createdPayment(Instant dueAt) {
        Payment payment = Payment.create(UUID.randomUUID(), UUID.randomUUID(), "customer-1", eur(1000), dueAt);
        return payments.save(payment);
    }

    private Set<UUID> idsOf(List<Payment> claimed) {
        return claimed.stream().map(Payment::id).collect(java.util.stream.Collectors.toSet());
    }

    // ---------------------------------------------------------------------------------------------- what is claimed

    @Test
    void claimsOnlyDuePaymentsOfTheRequestedStatusOldestFirst() {
        Payment older = createdPayment(at(-20));
        Payment newer = createdPayment(at(-10));
        createdPayment(at(1)); // not due yet
        createdPayment(at(0)); // due exactly now: claimed too
        Payment withIntent = Payment.create(UUID.randomUUID(), UUID.randomUUID(), "c", eur(1), at(-30));
        withIntent.attachPaymentIntent("pi_claim", at(-29));
        payments.save(withIntent); // no work any more

        List<Payment> claimed = inTransaction(() -> payments.claimDueBatch(PaymentStatus.CREATED, NOW, 10));

        assertThat(claimed).hasSize(3);
        assertThat(claimed.get(0).id()).isEqualTo(older.id());
        assertThat(claimed.get(1).id()).isEqualTo(newer.id());
        assertThat(claimed).allSatisfy(p -> {
            assertThat(p.status()).isEqualTo(PaymentStatus.CREATED);
            assertThat(p.isDue(NOW)).isTrue();
        });
    }

    @Test
    void theLimitCapsTheBatch() {
        IntStream.range(0, 7).forEach(i -> createdPayment(at(-100 + i)));

        List<Payment> claimed = inTransaction(() -> payments.claimDueBatch(PaymentStatus.CREATED, NOW, 3));

        assertThat(claimed).hasSize(3);
        assertThat(claimed).extracting(Payment::createdAt).isSorted();
    }

    @Test
    void anotherStatusHasItsOwnQueue() {
        Payment canceling = Payment.create(UUID.randomUUID(), UUID.randomUUID(), "c", eur(1), at(-30));
        canceling.attachPaymentIntent("pi_cancel", at(-29));
        canceling.requestCancel(at(-5));
        payments.save(canceling);
        createdPayment(at(-5));

        List<Payment> cancellations =
                inTransaction(() -> payments.claimDueBatch(PaymentStatus.REQUIRES_PAYMENT_METHOD, NOW, 10));
        List<Payment> initiations = inTransaction(() -> payments.claimDueBatch(PaymentStatus.CREATED, NOW, 10));

        assertThat(cancellations).singleElement().satisfies(p -> {
            assertThat(p.id()).isEqualTo(canceling.id());
            assertThat(p.cancelRequested()).isTrue();
        });
        assertThat(initiations).hasSize(1);
    }

    @Test
    void nothingDueMeansAnEmptyBatch() {
        createdPayment(at(60));

        assertThat(inTransaction(() -> payments.claimDueBatch(PaymentStatus.CREATED, NOW, 10)))
                .isEmpty();
        assertThat(inTransaction(() -> payments.claimDueBatch(PaymentStatus.PROCESSING, NOW, 10)))
                .isEmpty();
    }

    @Test
    void aClaimedPaymentCarriesItsHistoryAndVersion() {
        createdPayment(at(-1));

        Payment claimed = inTransaction(() -> payments.claimDueBatch(PaymentStatus.CREATED, NOW, 1))
                .getFirst();

        assertThat(claimed.version()).isZero();
        assertThat(claimed.history()).hasSize(1);
        assertThat(claimed.amount()).isEqualTo(eur(1000));
    }

    @Test
    void aClaimOutsideATransactionIsRefusedBecauseItsLocksWouldBeWorthless() {
        createdPayment(at(-1));

        assertThatThrownBy(() -> payments.claimDueBatch(PaymentStatus.CREATED, NOW, 1))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> refunds.claimDueBatch(RefundStatus.REQUESTED, NOW, 1))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> webhookEvents.claimDueBatch(NOW, 1))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void theLimitMustBePositive() {
        // the @Repository proxies translate IllegalArgumentException into Spring's data-access exception
        assertThatThrownBy(() -> inTransaction(() -> payments.claimDueBatch(PaymentStatus.CREATED, NOW, 0)))
                .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("limit must be at least 1");
        assertThatThrownBy(() -> inTransaction(() -> refunds.claimDueBatch(RefundStatus.REQUESTED, NOW, 0)))
                .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("limit must be at least 1");
        assertThatThrownBy(() -> inTransaction(() -> webhookEvents.claimDueBatch(NOW, -1)))
                .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("limit must be at least 1");
    }

    // ---------------------------------------------------------------------------------------------- concurrency

    @Test
    void twoWorkersClaimingAtTheSameTimeGetDisjointBatches() throws Exception {
        List<UUID> all = IntStream.range(0, 10)
                .mapToObj(i -> createdPayment(at(-100 + i)).id())
                .toList();
        CountDownLatch bothClaimed = new CountDownLatch(2);

        Set<UUID> first = new HashSet<>();
        Set<UUID> second = new HashSet<>();
        CompletableFuture<Void> a = CompletableFuture.runAsync(() -> claimAndHold(first, bothClaimed, 5));
        CompletableFuture<Void> b = CompletableFuture.runAsync(() -> claimAndHold(second, bothClaimed, 5));
        a.get(60, TimeUnit.SECONDS);
        b.get(60, TimeUnit.SECONDS);

        assertThat(first).hasSize(5);
        assertThat(second).hasSize(5);
        assertThat(Collections.disjoint(first, second))
                .as("no payment was claimed twice")
                .isTrue();
        Set<UUID> union = new HashSet<>(first);
        union.addAll(second);
        assertThat(union).containsExactlyInAnyOrderElementsOf(all);
    }

    /**
     * Claims inside a transaction and keeps the transaction open until the other worker has claimed too, so the two
     * claims genuinely overlap and the second one has to skip the rows the first holds.
     */
    private void claimAndHold(Set<UUID> into, CountDownLatch bothClaimed, int limit) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            into.addAll(idsOf(payments.claimDueBatch(PaymentStatus.CREATED, NOW, limit)));
            bothClaimed.countDown();
            try {
                assertThat(bothClaimed.await(30, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @Test
    void aWorkerDoesNotWaitForRowsAnotherOneHolds() throws Exception {
        createdPayment(at(-2));
        createdPayment(at(-1));
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Set<UUID> held = new HashSet<>();
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    held.addAll(idsOf(payments.claimDueBatch(PaymentStatus.CREATED, NOW, 10)));
                    holding.countDown();
                    try {
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
        assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        List<Payment> meanwhile = inTransaction(() -> payments.claimDueBatch(PaymentStatus.CREATED, NOW, 10));
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        release.countDown();
        holder.get(30, TimeUnit.SECONDS);

        assertThat(held).hasSize(2);
        assertThat(meanwhile)
                .as("SKIP LOCKED: the rows are held, so there is nothing to take")
                .isEmpty();
        assertThat(took).as("and it did not wait for them").isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void severalWorkersDrainingTheQueueProcessEveryPaymentExactlyOnce() throws Exception {
        int total = 60;
        List<UUID> all = IntStream.range(0, total)
                .mapToObj(i -> createdPayment(at(-1000 + i)).id())
                .toList();
        Duration lease = Duration.ofMinutes(2);
        List<UUID> processed = Collections.synchronizedList(new ArrayList<>());

        List<CompletableFuture<Void>> workers = IntStream.range(0, 4)
                .mapToObj(w -> CompletableFuture.runAsync(() -> {
                    while (true) {
                        // the shape of a real worker: claim and lease in one transaction ...
                        List<Payment> batch = new TransactionTemplate(transactionManager).execute(status -> {
                            List<Payment> claimed = payments.claimDueBatch(PaymentStatus.CREATED, NOW, 4);
                            claimed.forEach(p -> {
                                p.leaseUntil(NOW.plus(lease));
                                payments.save(p);
                            });
                            return claimed;
                        });
                        if (batch.isEmpty()) {
                            return;
                        }
                        // ... then do the work (Stripe) outside any transaction
                        batch.forEach(p -> processed.add(p.id()));
                    }
                }))
                .toList();
        for (CompletableFuture<Void> worker : workers) {
            worker.get(120, TimeUnit.SECONDS);
        }

        assertThat(processed).hasSize(total).doesNotHaveDuplicates();
        assertThat(processed).containsExactlyInAnyOrderElementsOf(all);
    }

    // ---------------------------------------------------------------------------------------------- the lease

    @Test
    void aLeaseKeepsAPaymentClaimedAfterTheTransactionHasEnded() {
        Payment due = createdPayment(at(-1));
        inTransaction(() -> {
            Payment claimed =
                    payments.claimDueBatch(PaymentStatus.CREATED, NOW, 5).getFirst();
            claimed.leaseUntil(at(120));
            return payments.save(claimed);
        });

        assertThat(inTransaction(() -> payments.claimDueBatch(PaymentStatus.CREATED, NOW, 5)))
                .as("leased: not due until the lease ends")
                .isEmpty();
        assertThat(inTransaction(() -> payments.claimDueBatch(PaymentStatus.CREATED, at(119), 5)))
                .isEmpty();
        assertThat(inTransaction(() -> payments.claimDueBatch(PaymentStatus.CREATED, at(120), 5)))
                .as("the worker died: after the lease somebody else takes over")
                .extracting(Payment::id)
                .containsExactly(due.id());
    }

    @Test
    void aWorkerThatFinishesWithinItsLeaseTakesThePaymentOutOfTheQueue() {
        Payment due = createdPayment(at(-1));
        Payment leased = inTransaction(() -> {
            Payment claimed =
                    payments.claimDueBatch(PaymentStatus.CREATED, NOW, 5).getFirst();
            claimed.leaseUntil(at(120));
            return payments.save(claimed);
        });

        // Stripe was called outside any transaction; now the result is stored
        leased.attachPaymentIntent("pi_worker", at(3));
        payments.save(leased);

        assertThat(inTransaction(() -> payments.claimDueBatch(PaymentStatus.CREATED, at(10_000), 5)))
                .isEmpty();
        assertThat(payments.findById(due.id()).orElseThrow().nextAttemptAt()).isNull();
    }

    @Test
    void aLeaseAndTheSlowWorkersLateResultCannotBothWin() {
        createdPayment(at(-1));
        Payment first = inTransaction(() -> {
            Payment claimed =
                    payments.claimDueBatch(PaymentStatus.CREATED, NOW, 5).getFirst();
            claimed.leaseUntil(at(60));
            return payments.save(claimed);
        });
        // the lease expires; a second worker claims, leases and finishes
        inTransaction(() -> {
            Payment claimed =
                    payments.claimDueBatch(PaymentStatus.CREATED, at(61), 5).getFirst();
            claimed.leaseUntil(at(200));
            return payments.save(claimed);
        });
        Payment second = payments.findById(first.id()).orElseThrow();
        second.attachPaymentIntent("pi_second", at(62));
        payments.save(second);

        // the slow first worker finally reports: its copy is stale
        first.attachPaymentIntent("pi_first", at(63));
        assertThatThrownBy(() -> payments.save(first))
                .isInstanceOf(com.altronixsoft.opp.payment.application.PaymentConcurrentlyModifiedException.class);
        assertThat(payments.findById(first.id()).orElseThrow().stripePaymentIntentId())
                .isEqualTo("pi_second");
    }

    // ---------------------------------------------------------------------------------------------- refunds

    private Refund requestedRefund(Instant dueAt) {
        Payment payment = Payment.create(UUID.randomUUID(), UUID.randomUUID(), "c", eur(500), NOW);
        payment.attachPaymentIntent("pi_" + UUID.randomUUID(), at(1));
        payment.applyStripeStatus("succeeded", at(2), PaymentStatusSource.WEBHOOK);
        payments.save(payment);
        return refunds.save(
                Refund.request(UUID.randomUUID(), payment.id(), UUID.randomUUID(), eur(500), "ADMIN", dueAt));
    }

    @Test
    void refundsAreClaimedByStatusAndDueTimeToo() {
        Refund due = requestedRefund(at(-5));
        requestedRefund(at(5));
        Refund pending = requestedRefund(at(-5));
        pending.markPending("re_p", at(-4));
        refunds.save(pending);

        List<Refund> requested = inTransaction(() -> refunds.claimDueBatch(RefundStatus.REQUESTED, NOW, 10));

        assertThat(requested).extracting(Refund::id).containsExactly(due.id());
        assertThat(inTransaction(() -> refunds.claimDueBatch(RefundStatus.PENDING, NOW, 10)))
                .as("a pending refund waits for its webhook, not for a worker")
                .isEmpty();
    }

    @Test
    void twoRefundWorkersGetDisjointBatches() throws Exception {
        IntStream.range(0, 8).forEach(i -> requestedRefund(at(-100 + i)));
        CountDownLatch bothClaimed = new CountDownLatch(2);
        Set<UUID> first = Collections.synchronizedSet(new HashSet<>());
        Set<UUID> second = Collections.synchronizedSet(new HashSet<>());

        CompletableFuture<Void> a = CompletableFuture.runAsync(() -> claimRefundsAndHold(first, bothClaimed));
        CompletableFuture<Void> b = CompletableFuture.runAsync(() -> claimRefundsAndHold(second, bothClaimed));
        a.get(60, TimeUnit.SECONDS);
        b.get(60, TimeUnit.SECONDS);

        assertThat(first).hasSize(4);
        assertThat(second).hasSize(4);
        assertThat(Collections.disjoint(first, second)).isTrue();
    }

    private void claimRefundsAndHold(Set<UUID> into, CountDownLatch bothClaimed) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            refunds.claimDueBatch(RefundStatus.REQUESTED, NOW, 4).forEach(r -> into.add(r.id()));
            bothClaimed.countDown();
            try {
                assertThat(bothClaimed.await(30, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    // ---------------------------------------------------------------------------------------------- webhook events

    private StripeWebhookEvent received(String id, Instant at) {
        return StripeWebhookEvent.receive(
                id,
                "payment_intent.succeeded",
                "2025-01-27.acacia",
                false,
                at.truncatedTo(ChronoUnit.MICROS),
                "{\"id\":\"" + id + "\",\"n\":{\"a\":[1,2]}}",
                at);
    }

    @Test
    void aWebhookEventIsStoredOnceAndARedeliveryIsANoOp() {
        StripeWebhookEvent event = received("evt_1", at(0));

        assertThat(webhookEvents.insertIfAbsent(event)).isTrue();
        StripeWebhookEvent changed = StripeWebhookEvent.receive(
                "evt_1",
                "payment_intent.payment_failed",
                null,
                false,
                at(5),
                "{\"id\":\"evt_1\",\"other\":true}",
                at(9));
        assertThat(webhookEvents.insertIfAbsent(changed))
                .as("same id: a redelivery")
                .isFalse();

        assertThat(jdbc.sql("SELECT count(*) FROM stripe_webhook_event")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        StripeWebhookEvent stored = webhookEvents.findById("evt_1").orElseThrow();
        assertThat(stored.type()).as("the first delivery is kept").isEqualTo("payment_intent.succeeded");
        assertThat(stored.status()).isEqualTo(WebhookEventStatus.RECEIVED);
        assertThat(stored.apiVersion()).isEqualTo("2025-01-27.acacia");
        assertThat(stored.livemode()).isFalse();
        assertThat(stored.receivedAt()).isEqualTo(at(0));
        assertThat(stored.nextAttemptAt()).isEqualTo(at(0));
    }

    @Test
    void thePayloadIsStoredAsJsonb() {
        webhookEvents.insertIfAbsent(received("evt_json", at(0)));

        assertThat(jdbc.sql("SELECT payload->'n'->'a'->>1 FROM stripe_webhook_event WHERE event_id = 'evt_json'")
                        .query(String.class)
                        .single())
                .isEqualTo("2");
        assertThat(jdbc.sql(
                                "SELECT data_type FROM information_schema.columns WHERE table_name = 'stripe_webhook_event' AND column_name = 'payload'")
                        .query(String.class)
                        .single())
                .isEqualTo("jsonb");
        assertThat(webhookEvents.findById("evt_json").orElseThrow().payload()).contains("\"evt_json\"");
    }

    @Test
    void concurrentRedeliveriesStoreTheEventOnce() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<Boolean>> deliveries = IntStream.range(0, 8)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return webhookEvents.insertIfAbsent(received("evt_race", at(0)));
                }))
                .toList();
        go.countDown();

        long inserted =
                deliveries.stream().map(CompletableFuture::join).filter(b -> b).count();

        assertThat(inserted).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM stripe_webhook_event")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void theProcessingStateIsSavedButTheReceivedEventIsNot() {
        webhookEvents.insertIfAbsent(received("evt_2", at(0)));
        StripeWebhookEvent event = webhookEvents.findById("evt_2").orElseThrow();
        event.scheduleRetry(
                at(10),
                new com.altronixsoft.opp.payment.domain.RetryPolicy(Duration.ofSeconds(2), Duration.ofMinutes(5), 3, 0),
                new java.util.Random(1),
                "no payment yet");
        webhookEvents.save(event);

        StripeWebhookEvent failed = webhookEvents.findById("evt_2").orElseThrow();
        assertThat(failed.status()).isEqualTo(WebhookEventStatus.FAILED);
        assertThat(failed.attempts()).isEqualTo(1);
        assertThat(failed.nextAttemptAt()).isEqualTo(at(12));
        assertThat(failed.lastError()).isEqualTo("no payment yet");

        failed.markProcessed(at(13));
        webhookEvents.save(failed);
        StripeWebhookEvent done = webhookEvents.findById("evt_2").orElseThrow();
        assertThat(done.status()).isEqualTo(WebhookEventStatus.PROCESSED);
        assertThat(done.processedAt()).isEqualTo(at(13));
        assertThat(done.nextAttemptAt()).isNull();
        assertThat(done.payload()).contains("evt_2");
    }

    @Test
    void savingAnUnknownEventIsAnError() {
        StripeWebhookEvent never = received("evt_never", at(0));

        assertThatThrownBy(() -> webhookEvents.save(never))
                .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("evt_never");
    }

    @Test
    void onlyReceivedAndFailedEventsThatAreDueAreClaimed() {
        webhookEvents.insertIfAbsent(received("evt_a", at(-30)));
        webhookEvents.insertIfAbsent(received("evt_b", at(-20)));
        webhookEvents.insertIfAbsent(received("evt_future", at(60)));
        webhookEvents.insertIfAbsent(received("evt_done", at(-40)));
        StripeWebhookEvent done = webhookEvents.findById("evt_done").orElseThrow();
        done.markProcessed(at(-39));
        webhookEvents.save(done);
        webhookEvents.insertIfAbsent(received("evt_failed", at(-50)));
        StripeWebhookEvent failed = webhookEvents.findById("evt_failed").orElseThrow();
        failed.scheduleRetry(
                at(-49),
                new com.altronixsoft.opp.payment.domain.RetryPolicy(Duration.ofSeconds(2), Duration.ofMinutes(5), 5, 0),
                new java.util.Random(1),
                "e");
        webhookEvents.save(failed);
        webhookEvents.insertIfAbsent(received("evt_dead", at(-60)));
        StripeWebhookEvent dead = webhookEvents.findById("evt_dead").orElseThrow();
        dead.scheduleRetry(
                at(-59),
                new com.altronixsoft.opp.payment.domain.RetryPolicy(Duration.ofSeconds(2), Duration.ofMinutes(5), 1, 0),
                new java.util.Random(1),
                "e");
        webhookEvents.save(dead);

        List<StripeWebhookEvent> claimed = inTransaction(() -> webhookEvents.claimDueBatch(NOW, 10));

        assertThat(claimed).extracting(StripeWebhookEvent::eventId).containsExactly("evt_failed", "evt_a", "evt_b");
        assertThat(inTransaction(() -> webhookEvents.claimDueBatch(NOW, 2))).hasSize(2);
    }

    @Test
    void twoWebhookProcessorsGetDisjointBatchesAndALeaseHoldsTheEvents() throws Exception {
        IntStream.range(0, 6).forEach(i -> webhookEvents.insertIfAbsent(received("evt_" + i, at(-100 + i))));
        CountDownLatch bothClaimed = new CountDownLatch(2);
        Set<String> first = Collections.synchronizedSet(new HashSet<>());
        Set<String> second = Collections.synchronizedSet(new HashSet<>());

        CompletableFuture<Void> a = CompletableFuture.runAsync(() -> claimEventsAndHold(first, bothClaimed));
        CompletableFuture<Void> b = CompletableFuture.runAsync(() -> claimEventsAndHold(second, bothClaimed));
        a.get(60, TimeUnit.SECONDS);
        b.get(60, TimeUnit.SECONDS);

        assertThat(first).hasSize(3);
        assertThat(second).hasSize(3);
        assertThat(Collections.disjoint(first, second)).isTrue();

        inTransaction(() -> {
            webhookEvents.claimDueBatch(NOW, 10).forEach(e -> {
                e.leaseUntil(at(300));
                webhookEvents.save(e);
            });
            return null;
        });
        assertThat(inTransaction(() -> webhookEvents.claimDueBatch(NOW, 10))).isEmpty();
        assertThat(inTransaction(() -> webhookEvents.claimDueBatch(at(300), 10)))
                .hasSize(6);
    }

    private void claimEventsAndHold(Set<String> into, CountDownLatch bothClaimed) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            webhookEvents.claimDueBatch(NOW, 3).forEach(e -> into.add(e.eventId()));
            bothClaimed.countDown();
            try {
                assertThat(bothClaimed.await(30, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @Test
    void theDatabaseNeverStoresALiveModeEvent() {
        assertThatThrownBy(() -> jdbc.sql(
                                "INSERT INTO stripe_webhook_event (event_id, type, livemode, stripe_created_at, payload, status, received_at) "
                                        + "VALUES ('evt_live', 't', true, now(), '{}', 'RECEIVED', now())")
                        .update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
