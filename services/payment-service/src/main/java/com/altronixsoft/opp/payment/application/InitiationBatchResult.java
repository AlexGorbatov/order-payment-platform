package com.altronixsoft.opp.payment.application;

import java.util.EnumMap;
import java.util.Map;

/** How many payments of one run ended in which {@link InitiationOutcome}. */
public record InitiationBatchResult(Map<InitiationOutcome, Integer> counts) {

    public InitiationBatchResult {
        counts = Map.copyOf(counts);
    }

    public static InitiationBatchResult empty() {
        return new InitiationBatchResult(Map.of());
    }

    public int count(InitiationOutcome outcome) {
        return counts.getOrDefault(outcome, 0);
    }

    public int total() {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Collects outcomes while a run is in progress. */
    static final class Builder {
        private final Map<InitiationOutcome, Integer> counts = new EnumMap<>(InitiationOutcome.class);

        void add(InitiationOutcome outcome) {
            counts.merge(outcome, 1, Integer::sum);
        }

        InitiationBatchResult build() {
            return new InitiationBatchResult(counts);
        }
    }
}
