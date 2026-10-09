package com.altronixsoft.opp.payment.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.random.RandomGenerator;

/**
 * Exponential backoff with jitter for the work queues of architecture §7.6.
 *
 * <p>After the n-th failed attempt the work is due again after {@code min(maxDelay, baseDelay · 2^(n−1))}, reduced by a
 * random fraction of at most {@code jitter} so that items that failed together do not retry together. The jittered
 * delay therefore lies in {@code [delay · (1 − jitter), delay]} and never exceeds {@code maxDelay}. After
 * {@code maxAttempts} failures the work is exhausted.
 *
 * @param baseDelay delay after the first failure
 * @param maxDelay upper bound of any delay
 * @param maxAttempts failed attempts after which the work is exhausted (at least 1)
 * @param jitter fraction in {@code [0, 1)} of the delay that may be shaved off at random
 */
public record RetryPolicy(Duration baseDelay, Duration maxDelay, int maxAttempts, double jitter) {

    /** 2 s, 4 s, 8 s … capped at 5 minutes, 8 attempts, up to 20 % jitter. */
    public static final RetryPolicy DEFAULT = new RetryPolicy(Duration.ofSeconds(2), Duration.ofMinutes(5), 8, 0.2);

    public RetryPolicy {
        if (baseDelay == null || baseDelay.isNegative() || baseDelay.isZero()) {
            throw new IllegalArgumentException("baseDelay must be positive");
        }
        if (maxDelay == null || maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("maxDelay must not be shorter than baseDelay");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        if (!(jitter >= 0 && jitter < 1)) {
            throw new IllegalArgumentException("jitter must be in [0, 1), got " + jitter);
        }
    }

    /** The delay without jitter after the {@code failedAttempts}-th failure (1 for the first). */
    public Duration delayWithoutJitter(int failedAttempts) {
        if (failedAttempts < 1) {
            throw new IllegalArgumentException("failedAttempts must be at least 1");
        }
        long maxMillis = maxDelay.toMillis();
        long millis = baseDelay.toMillis();
        for (int i = 1; i < failedAttempts && millis < maxMillis; i++) {
            millis = millis > maxMillis / 2 ? maxMillis : millis * 2;
        }
        return Duration.ofMillis(Math.min(millis, maxMillis));
    }

    /** The delay after the {@code failedAttempts}-th failure, jitter included. */
    public Duration delayAfter(int failedAttempts, RandomGenerator random) {
        long millis = delayWithoutJitter(failedAttempts).toMillis();
        long shaved = (long) (millis * jitter * random.nextDouble());
        return Duration.ofMillis(millis - shaved);
    }

    /** When the work is due after the {@code failedAttempts}-th failure that happened at {@code now}. */
    public Instant nextAttemptAfter(int failedAttempts, Instant now, RandomGenerator random) {
        return now.plus(delayAfter(failedAttempts, random));
    }

    /** Whether {@code failedAttempts} failures use up the policy. */
    public boolean isExhaustedAfter(int failedAttempts) {
        return failedAttempts >= maxAttempts;
    }
}
