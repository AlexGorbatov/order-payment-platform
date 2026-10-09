package com.altronixsoft.opp.payment.application;

import com.altronixsoft.opp.payment.domain.PaymentStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What one reconciliation run found.
 *
 * @param trigger {@code SCHEDULED} or {@code MANUAL}
 * @param checked payments whose PaymentIntent was retrieved from Stripe
 * @param drifted payments whose status Stripe reported differently, and which were corrected (a webhook was lost or
 *     late)
 * @param unchanged payments Stripe agreed with (or reported something older than what was known meanwhile)
 * @param failed payments that could not be checked (Stripe unavailable, an unusable status); checked again next run
 * @param deferred payments left for the next run because the rate limit gave no permit in time
 * @param drifts what changed, payment by payment
 */
public record ReconciliationSummary(
        Trigger trigger,
        Instant startedAt,
        Instant finishedAt,
        int checked,
        int drifted,
        int unchanged,
        int failed,
        int deferred,
        List<Drift> drifts) {

    /** What started a run. */
    public enum Trigger {
        SCHEDULED,
        MANUAL
    }

    /** One corrected payment. */
    public record Drift(UUID paymentId, PaymentStatus from, PaymentStatus to) {}

    public ReconciliationSummary {
        Objects.requireNonNull(trigger, "trigger");
        drifts = List.copyOf(drifts);
    }

    /** Collects a run. */
    static final class Builder {
        private final Trigger trigger;
        private final Instant startedAt;
        private int checked;
        private int unchanged;
        private int failed;
        private int deferred;
        private final List<Drift> drifts = new ArrayList<>();

        Builder(Trigger trigger, Instant startedAt) {
            this.trigger = trigger;
            this.startedAt = startedAt;
        }

        void checked() {
            checked++;
        }

        void unchanged() {
            unchanged++;
        }

        void failed() {
            failed++;
        }

        void deferred() {
            deferred++;
        }

        void drift(UUID paymentId, PaymentStatus from, PaymentStatus to) {
            drifts.add(new Drift(paymentId, from, to));
        }

        ReconciliationSummary build(Instant finishedAt) {
            return new ReconciliationSummary(
                    trigger, startedAt, finishedAt, checked, drifts.size(), unchanged, failed, deferred, drifts);
        }
    }
}
