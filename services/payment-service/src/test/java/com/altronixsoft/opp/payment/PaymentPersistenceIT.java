package com.altronixsoft.opp.payment;

import static com.altronixsoft.opp.payment.domain.PaymentFixtures.eur;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.altronixsoft.opp.payment.application.DuplicatePaymentException;
import com.altronixsoft.opp.payment.application.DuplicateRefundException;
import com.altronixsoft.opp.payment.application.PaymentConcurrentlyModifiedException;
import com.altronixsoft.opp.payment.application.RefundConcurrentlyModifiedException;
import com.altronixsoft.opp.payment.domain.Payment;
import com.altronixsoft.opp.payment.domain.PaymentStatus;
import com.altronixsoft.opp.payment.domain.PaymentStatusChange;
import com.altronixsoft.opp.payment.domain.PaymentStatusSource;
import com.altronixsoft.opp.payment.domain.Refund;
import com.altronixsoft.opp.payment.domain.RefundStatus;
import com.altronixsoft.opp.payment.domain.RetryPolicy;
import com.altronixsoft.opp.payment.domain.StripeOutcome;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Round trips, optimistic locking, uniqueness and the CHECK constraints of {@code payments_db}. */
class PaymentPersistenceIT extends AbstractPersistenceIT {

    /** PostgreSQL keeps microseconds; the tests use instants that survive the round trip unchanged. */
    private static final Instant T0 = Instant.parse("2026-10-08T12:00:00.123456Z");

    private static Instant at(int seconds) {
        return T0.plusSeconds(seconds).truncatedTo(ChronoUnit.MICROS);
    }

    private static Payment newPayment() {
        return Payment.create(UUID.randomUUID(), UUID.randomUUID(), "customer-1", eur(3097), T0);
    }

    private Payment stored(Payment payment) {
        return payments.save(payment);
    }

    // ---------------------------------------------------------------------------------------------- payment

    @Test
    void flywayCreatesTheSchemaOfArchitectureSection10() {
        assertThat(jdbc.sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY 1")
                        .query(String.class)
                        .list())
                .contains("payment", "refund", "payment_status_history", "stripe_webhook_event");
        assertThat(jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE success")
                        .query(Integer.class)
                        .single())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void aNewPaymentRoundTripsWithEveryField() {
        Payment payment = newPayment();

        Payment saved = stored(payment);

        assertThat(saved.version()).isZero();
        Payment loaded = payments.findById(payment.id()).orElseThrow();
        assertThat(loaded.id()).isEqualTo(payment.id());
        assertThat(loaded.orderId()).isEqualTo(payment.orderId());
        assertThat(loaded.customerId()).isEqualTo("customer-1");
        assertThat(loaded.amount()).isEqualTo(eur(3097));
        assertThat(loaded.status()).isEqualTo(PaymentStatus.CREATED);
        assertThat(loaded.stripePaymentIntentId()).isNull();
        assertThat(loaded.lastStripeEventAt()).isNull();
        assertThat(loaded.cancelRequested()).isFalse();
        assertThat(loaded.disputed()).isFalse();
        assertThat(loaded.attempts()).isZero();
        assertThat(loaded.nextAttemptAt()).isEqualTo(T0.truncatedTo(ChronoUnit.MICROS));
        assertThat(loaded.createdAt()).isEqualTo(T0.truncatedTo(ChronoUnit.MICROS));
        assertThat(loaded.history()).isEqualTo(payment.history());
        assertThat(loaded.version()).isZero();
    }

    @Test
    void aPaymentThatWentThroughItsLifeRoundTripsToo() {
        Payment payment = stored(newPayment());
        payment.attachPaymentIntent("pi_100", at(1));
        payment.applyStripeStatus("requires_action", at(2), PaymentStatusSource.WEBHOOK, "evt_a");
        payment.applyStripeStatus("processing", at(3), PaymentStatusSource.WEBHOOK, "evt_b");
        payment.applyStripeStatus("succeeded", at(4), PaymentStatusSource.WEBHOOK, "evt_c");
        payment.markDisputed(at(5));
        payment.recordError("card_declined", "Your card was declined.", at(5));

        Payment saved = stored(payment);
        Payment loaded = payments.findById(payment.id()).orElseThrow();

        assertThat(saved.version()).isEqualTo(1);
        assertThat(loaded.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(loaded.stripePaymentIntentId()).isEqualTo("pi_100");
        assertThat(loaded.lastStripeEventAt()).isEqualTo(at(4));
        assertThat(loaded.disputed()).isTrue();
        assertThat(loaded.lastErrorCode()).isEqualTo("card_declined");
        assertThat(loaded.lastErrorMessage()).isEqualTo("Your card was declined.");
        assertThat(loaded.nextAttemptAt()).isNull();
        assertThat(loaded.history())
                .extracting(PaymentStatusChange::to, PaymentStatusChange::source, PaymentStatusChange::stripeEventId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(PaymentStatus.CREATED, PaymentStatusSource.LOCAL, null),
                        org.assertj.core.groups.Tuple.tuple(
                                PaymentStatus.REQUIRES_PAYMENT_METHOD, PaymentStatusSource.STRIPE_API, null),
                        org.assertj.core.groups.Tuple.tuple(
                                PaymentStatus.REQUIRES_ACTION, PaymentStatusSource.WEBHOOK, "evt_a"),
                        org.assertj.core.groups.Tuple.tuple(
                                PaymentStatus.PROCESSING, PaymentStatusSource.WEBHOOK, "evt_b"),
                        org.assertj.core.groups.Tuple.tuple(
                                PaymentStatus.SUCCEEDED, PaymentStatusSource.WEBHOOK, "evt_c"));
    }

    @Test
    void theWatermarkAndTheOrderingRuleSurviveTheDatabase() {
        Payment payment = newPayment();
        payment.attachPaymentIntent("pi_101", at(1));
        payment.applyStripeStatus("succeeded", at(9), PaymentStatusSource.WEBHOOK);
        stored(payment);

        Payment loaded = payments.findById(payment.id()).orElseThrow();

        assertThat(loaded.applyStripeStatus("processing", at(8), PaymentStatusSource.WEBHOOK))
                .isEqualTo(StripeOutcome.STALE_IGNORED);
        assertThat(loaded.applyStripeStatus("requires_payment_method", at(10), PaymentStatusSource.WEBHOOK))
                .isEqualTo(StripeOutcome.STALE_IGNORED);
        assertThat(loaded.status()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void theHistoryIsAppendedNotRewritten() {
        Payment payment = stored(newPayment());
        long firstRowId = jdbc.sql("SELECT min(id) FROM payment_status_history")
                .query(Long.class)
                .single();

        payment.attachPaymentIntent("pi_102", at(1));
        payment = stored(payment);
        payment.applyStripeStatus("processing", at(2), PaymentStatusSource.WEBHOOK, "evt_1");
        stored(payment);

        assertThat(jdbc.sql("SELECT count(*) FROM payment_status_history")
                        .query(Integer.class)
                        .single())
                .isEqualTo(3);
        assertThat(jdbc.sql("SELECT min(id) FROM payment_status_history")
                        .query(Long.class)
                        .single())
                .as("the first row is the same row")
                .isEqualTo(firstRowId);
        assertThat(jdbc.sql("SELECT to_status FROM payment_status_history ORDER BY id")
                        .query(String.class)
                        .list())
                .containsExactly("CREATED", "REQUIRES_PAYMENT_METHOD", "PROCESSING");
    }

    @Test
    void saveReturnsTheStoredStateWithTheNewVersion() {
        Payment payment = newPayment();
        Payment first = stored(payment);
        first.attachPaymentIntent("pi_103", at(1));

        Payment second = stored(first);

        assertThat(first.version()).isZero();
        assertThat(second.version()).isEqualTo(1);
        assertThat(second.stripePaymentIntentId()).isEqualTo("pi_103");
    }

    @Test
    void errorMessagesAreStoredAtTheColumnSize() {
        Payment payment = newPayment();
        payment.recordError("c", "z".repeat(4000), at(1));

        stored(payment);

        assertThat(payments.findById(payment.id()).orElseThrow().lastErrorMessage())
                .hasSize(1024);
    }

    @Test
    void findsByOrderAndByPaymentIntent() {
        Payment payment = newPayment();
        payment.attachPaymentIntent("pi_104", at(1));
        stored(payment);

        assertThat(payments.findByOrderId(payment.orderId()))
                .hasValueSatisfying(p -> assertThat(p.id()).isEqualTo(payment.id()));
        assertThat(payments.findByStripePaymentIntentId("pi_104"))
                .hasValueSatisfying(p -> assertThat(p.id()).isEqualTo(payment.id()));
        assertThat(payments.findById(UUID.randomUUID())).isEmpty();
        assertThat(payments.findByOrderId(UUID.randomUUID())).isEmpty();
        assertThat(payments.findByStripePaymentIntentId("pi_nope")).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------- locking

    @Test
    void aStaleCopyCannotOverwriteANewerVersion() {
        Payment payment = stored(newPayment());
        Payment winner = payments.findById(payment.id()).orElseThrow();
        Payment loser = payments.findById(payment.id()).orElseThrow();
        winner.attachPaymentIntent("pi_105", at(1));
        stored(winner);

        loser.requestCancel(at(2));

        assertThatThrownBy(() -> stored(loser))
                .isInstanceOfSatisfying(
                        PaymentConcurrentlyModifiedException.class,
                        e -> assertThat(e.paymentId()).isEqualTo(payment.id()));
        Payment current = payments.findById(payment.id()).orElseThrow();
        assertThat(current.status()).isEqualTo(PaymentStatus.REQUIRES_PAYMENT_METHOD);
        assertThat(current.version()).isEqualTo(1);
    }

    @Test
    void aChangeCommittedBetweenTheVersionCheckAndTheFlushIsCaughtByTheVersionColumn() {
        Payment payment = stored(newPayment());
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        TransactionTemplate other = new TransactionTemplate(transactionManager);
        other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
                    Payment mine = payments.findById(payment.id()).orElseThrow();
                    // someone else commits a change in a separate transaction while this one is open
                    other.executeWithoutResult(inner -> {
                        Payment theirs = payments.findById(payment.id()).orElseThrow();
                        theirs.attachPaymentIntent("pi_106", at(1));
                        payments.save(theirs);
                    });
                    mine.requestCancel(at(2));
                    try {
                        payments.save(mine);
                    } catch (Throwable e) {
                        failure.set(e);
                    }
                }))
                .as("the failed save marked the surrounding transaction rollback-only")
                .isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);

        assertThat(failure.get()).isInstanceOf(PaymentConcurrentlyModifiedException.class);
        assertThat(payments.findById(payment.id()).orElseThrow().stripePaymentIntentId())
                .isEqualTo("pi_106");
    }

    @Test
    void aDeletedRowIsAConflictToo() {
        Payment payment = stored(newPayment());
        Payment loaded = payments.findById(payment.id()).orElseThrow();
        jdbc.sql("DELETE FROM payment_status_history").update();
        jdbc.sql("DELETE FROM payment").update();
        loaded.requestCancel(at(1));

        assertThatThrownBy(() -> stored(loaded)).isInstanceOf(PaymentConcurrentlyModifiedException.class);
    }

    // ---------------------------------------------------------------------------------------------- uniqueness

    @Test
    void thereIsOnePaymentPerOrder() {
        Payment first = stored(newPayment());
        Payment second = Payment.create(UUID.randomUUID(), first.orderId(), "customer-2", eur(100), T0);

        assertThatThrownBy(() -> stored(second))
                .isInstanceOfSatisfying(
                        DuplicatePaymentException.class,
                        e -> assertThat(e.constraint()).isEqualTo("payment_order_id_key"));
        assertThat(jdbc.sql("SELECT count(*) FROM payment").query(Integer.class).single())
                .isEqualTo(1);
    }

    @Test
    void aPaymentIntentBelongsToOnePayment() {
        Payment first = newPayment();
        first.attachPaymentIntent("pi_dup", at(1));
        stored(first);
        Payment second = newPayment();
        second.attachPaymentIntent("pi_dup", at(1));

        assertThatThrownBy(() -> stored(second))
                .isInstanceOfSatisfying(
                        DuplicatePaymentException.class,
                        e -> assertThat(e.constraint()).isEqualTo("payment_stripe_payment_intent_id_key"));
    }

    @Test
    void severalPaymentsWithoutAPaymentIntentAreFine() {
        stored(newPayment());
        stored(newPayment());

        assertThat(jdbc.sql("SELECT count(*) FROM payment WHERE stripe_payment_intent_id IS NULL")
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
    }

    // ---------------------------------------------------------------------------------------------- CHECK constraints

    @Test
    void theDatabaseRefusesWhatTheDomainWouldNeverProduce() {
        Payment payment = stored(newPayment());

        assertThatThrownBy(() -> jdbc.sql(
                                "UPDATE payment SET status = 'PAID', stripe_payment_intent_id = 'pi_x' WHERE id = ?")
                        .param(payment.id())
                        .update())
                .hasMessageContaining("payment_status_check");
        assertThatThrownBy(() -> jdbc.sql("UPDATE payment SET status = 'PROCESSING' WHERE id = ?")
                        .param(payment.id())
                        .update())
                .as("a PaymentIntent is required from REQUIRES_PAYMENT_METHOD on")
                .hasMessageContaining("payment_intent_present_check");
        assertThatThrownBy(() -> jdbc.sql("UPDATE payment SET amount_minor = 0 WHERE id = ?")
                        .param(payment.id())
                        .update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE payment SET attempts = -1 WHERE id = ?")
                        .param(payment.id())
                        .update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql(
                                "INSERT INTO payment_status_history (payment_id, to_status, source, occurred_at) VALUES (?, 'CREATED', 'MAGIC', now())")
                        .param(payment.id())
                        .update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @EnumSource(
            value = PaymentStatus.class,
            names = {"CREATED", "INITIATION_FAILED", "CANCELED"})
    void aPaymentThatNeverGotAPaymentIntentMayOnlyBeInTheStatusesBeforeOrOutsideOne(PaymentStatus status) {
        Payment payment = stored(newPayment());

        jdbc.sql("UPDATE payment SET status = ? WHERE id = ?")
                .params(status.name(), payment.id())
                .update();

        assertThat(payments.findById(payment.id()).orElseThrow().status()).isEqualTo(status);
    }

    // ---------------------------------------------------------------------------------------------- refunds

    private Payment succeededPayment() {
        Payment payment = newPayment();
        payment.attachPaymentIntent("pi_" + UUID.randomUUID(), at(1));
        payment.applyStripeStatus("succeeded", at(2), PaymentStatusSource.WEBHOOK);
        return stored(payment);
    }

    private Refund newRefund(Payment payment) {
        return Refund.request(UUID.randomUUID(), payment.id(), UUID.randomUUID(), payment.amount(), "ADMIN", T0);
    }

    @Test
    void aRefundRoundTrips() {
        Payment payment = succeededPayment();
        Refund refund = newRefund(payment);

        Refund saved = refunds.save(refund);
        saved.markPending("re_1", at(1));
        refunds.save(saved);
        Refund loaded = refunds.findById(refund.id()).orElseThrow();

        assertThat(loaded.paymentId()).isEqualTo(payment.id());
        assertThat(loaded.refundRequestId()).isEqualTo(refund.refundRequestId());
        assertThat(loaded.amount()).isEqualTo(eur(3097));
        assertThat(loaded.reason()).isEqualTo("ADMIN");
        assertThat(loaded.status()).isEqualTo(RefundStatus.PENDING);
        assertThat(loaded.stripeRefundId()).isEqualTo("re_1");
        assertThat(loaded.nextAttemptAt()).isNull();
        assertThat(loaded.version()).isEqualTo(1);
        assertThat(loaded.createdAt()).isEqualTo(T0.truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    void findsRefundsByRequestStripeIdAndPayment() {
        Payment payment = succeededPayment();
        Refund refund = newRefund(payment);
        refund.markPending("re_find", at(1));
        Refund request =
                Refund.request(refund.id(), refund.paymentId(), refund.refundRequestId(), refund.amount(), "ADMIN", T0);
        request.markPending("re_find", at(1));
        refunds.save(request);

        assertThat(refunds.findByRefundRequestId(refund.refundRequestId())).isPresent();
        assertThat(refunds.findByStripeRefundId("re_find")).isPresent();
        assertThat(refunds.findByPaymentId(payment.id())).hasSize(1);
        assertThat(refunds.findByRefundRequestId(UUID.randomUUID())).isEmpty();
        assertThat(refunds.findByStripeRefundId("re_nope")).isEmpty();
        assertThat(refunds.findByPaymentId(UUID.randomUUID())).isEmpty();
    }

    @Test
    void oneRefundPerRequestId() {
        Payment payment = succeededPayment();
        Refund first = newRefund(payment);
        refunds.save(first);
        Refund again =
                Refund.request(UUID.randomUUID(), payment.id(), first.refundRequestId(), payment.amount(), "ADMIN", T0);

        assertThatThrownBy(() -> refunds.save(again))
                .isInstanceOfSatisfying(
                        DuplicateRefundException.class,
                        e -> assertThat(e.constraint())
                                .isIn("refund_refund_request_id_key", "refund_one_open_per_payment"));
    }

    @Test
    void aRedeliveredRequestIsRecognisedByItsRequestIdEvenForAFailedRefund() {
        Payment payment = succeededPayment();
        Refund failed = newRefund(payment);
        failed.markFailed("card_closed", at(1));
        refunds.save(failed);
        Refund redelivered = Refund.request(
                UUID.randomUUID(), payment.id(), failed.refundRequestId(), payment.amount(), "ADMIN", T0);

        assertThatThrownBy(() -> refunds.save(redelivered))
                .isInstanceOfSatisfying(
                        DuplicateRefundException.class,
                        e -> assertThat(e.constraint()).isEqualTo("refund_refund_request_id_key"));
    }

    @Test
    void aStripeRefundBelongsToOneRefund() {
        Refund first = newRefund(succeededPayment());
        first.markPending("re_same", at(1));
        refunds.save(first);
        Refund second = newRefund(succeededPayment());
        second.markPending("re_same", at(1));

        assertThatThrownBy(() -> refunds.save(second))
                .isInstanceOfSatisfying(
                        DuplicateRefundException.class,
                        e -> assertThat(e.constraint()).isEqualTo("refund_stripe_refund_id_key"));
    }

    @Test
    void aPaymentCanHaveOnlyOneRefundThatHasNotFailedAndTheMoneyCannotGoBackTwice() {
        Payment payment = succeededPayment();
        refunds.save(newRefund(payment));

        assertThatThrownBy(() -> refunds.save(newRefund(payment)))
                .isInstanceOfSatisfying(
                        DuplicateRefundException.class,
                        e -> assertThat(e.constraint()).isEqualTo("refund_one_open_per_payment"));
    }

    @Test
    void afterAFailedRefundTheAdministratorMayRetryWithANewRequest() {
        Payment payment = succeededPayment();
        Refund failed = refunds.save(newRefund(payment));
        failed.markFailed("card_closed", at(1));
        refunds.save(failed);

        Refund retry = refunds.save(Refund.request(
                UUID.randomUUID(), payment.id(), UUID.randomUUID(), payment.amount(), "ADMIN", T0.plusSeconds(60)));

        assertThat(refunds.findByPaymentId(payment.id())).hasSize(2);
        assertThat(retry.status()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(refunds.findByPaymentId(payment.id()))
                .extracting(Refund::status)
                .containsExactly(RefundStatus.FAILED, RefundStatus.REQUESTED);
    }

    @Test
    void aSucceededRefundStillBlocksANewOne() {
        Payment payment = succeededPayment();
        Refund done = refunds.save(newRefund(payment));
        done.markPending("re_done", at(1));
        done = refunds.save(done);
        done.markSucceeded(at(2));
        refunds.save(done);

        assertThatThrownBy(() -> refunds.save(newRefund(payment))).isInstanceOf(DuplicateRefundException.class);
    }

    @Test
    void aRefundNeedsAnExistingPayment() {
        Refund orphan = Refund.request(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), eur(1), "ADMIN", T0);

        assertThatThrownBy(() -> refunds.save(orphan)).isInstanceOf(DuplicateRefundException.class);
    }

    @Test
    void aStaleRefundCannotOverwriteANewerVersion() {
        Payment payment = succeededPayment();
        Refund refund = refunds.save(newRefund(payment));
        Refund winner = refunds.findById(refund.id()).orElseThrow();
        Refund loser = refunds.findById(refund.id()).orElseThrow();
        winner.markPending("re_w", at(1));
        refunds.save(winner);
        loser.markFailed("late", at(2));

        assertThatThrownBy(() -> refunds.save(loser)).isInstanceOf(RefundConcurrentlyModifiedException.class);
        assertThat(refunds.findById(refund.id()).orElseThrow().status()).isEqualTo(RefundStatus.PENDING);
    }

    @Test
    void theRetryStateOfAPaymentAndARefundIsStored() {
        RetryPolicy policy = new RetryPolicy(Duration.ofSeconds(2), Duration.ofMinutes(5), 5, 0);
        RandomGenerator none = new java.util.Random(1);
        Payment payment = newPayment();
        payment.scheduleRetry(at(10), policy, none, "api_connection_error", "timeout");
        payment.scheduleRetry(at(20), policy, none, "api_connection_error", "timeout");
        stored(payment);
        Payment loaded = payments.findById(payment.id()).orElseThrow();

        assertThat(loaded.attempts()).isEqualTo(2);
        assertThat(loaded.nextAttemptAt()).isEqualTo(at(24));
        assertThat(loaded.lastErrorCode()).isEqualTo("api_connection_error");
    }
}
