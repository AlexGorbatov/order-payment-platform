package com.altronixsoft.opp.payment.application;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What one run of {@link ProcessWebhookEventsService} did.
 *
 * @param counts how many claimed events ended in which outcome
 * @param lags for every event that reached a final status in this run, the time from its arrival to that moment
 * @param dead the ids of the events that became {@code DEAD} in this run
 */
public record WebhookBatchResult(Map<WebhookOutcome, Integer> counts, List<Duration> lags, List<String> dead) {

    public WebhookBatchResult {
        counts = Map.copyOf(counts);
        lags = List.copyOf(lags);
        dead = List.copyOf(dead);
    }

    public int count(WebhookOutcome outcome) {
        return counts.getOrDefault(outcome, 0);
    }

    public int total() {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Collects outcomes while a run is in progress. */
    static final class Builder {
        private final Map<WebhookOutcome, Integer> counts = new EnumMap<>(WebhookOutcome.class);
        private final List<Duration> lags = new ArrayList<>();
        private final List<String> dead = new ArrayList<>();

        void add(WebhookOutcome outcome) {
            counts.merge(outcome, 1, Integer::sum);
        }

        void lag(Duration lag) {
            lags.add(lag);
        }

        void dead(String eventId) {
            dead.add(eventId);
        }

        WebhookBatchResult build() {
            return new WebhookBatchResult(counts, lags, dead);
        }
    }
}
