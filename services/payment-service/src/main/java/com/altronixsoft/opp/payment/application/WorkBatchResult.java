package com.altronixsoft.opp.payment.application;

import java.util.EnumMap;
import java.util.Map;

/** How many items of one worker run ended in which outcome. */
public record WorkBatchResult<O extends Enum<O>>(Map<O, Integer> counts) {

    public WorkBatchResult {
        counts = Map.copyOf(counts);
    }

    public int count(O outcome) {
        return counts.getOrDefault(outcome, 0);
    }

    public int total() {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Collects outcomes while a run is in progress. */
    static final class Builder<O extends Enum<O>> {
        private final Map<O, Integer> counts;

        Builder(Class<O> type) {
            counts = new EnumMap<>(type);
        }

        void add(O outcome) {
            counts.merge(outcome, 1, Integer::sum);
        }

        WorkBatchResult<O> build() {
            return new WorkBatchResult<>(counts);
        }
    }
}
