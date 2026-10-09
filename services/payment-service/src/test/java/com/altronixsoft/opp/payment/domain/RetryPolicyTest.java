package com.altronixsoft.opp.payment.domain;

import static com.altronixsoft.opp.payment.domain.PaymentFixtures.fixedRandom;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RetryPolicyTest {

    private static final RetryPolicy POLICY = new RetryPolicy(Duration.ofSeconds(2), Duration.ofMinutes(5), 8, 0.2);

    @ParameterizedTest(name = "after failure {0}: {1} s")
    @CsvSource({"1, 2", "2, 4", "3, 8", "4, 16", "5, 32", "6, 64", "7, 128", "8, 256", "9, 300", "10, 300", "50, 300"})
    void theDelayDoublesAndStopsAtTheCap(int failures, long expectedSeconds) {
        assertThat(POLICY.delayWithoutJitter(failures)).isEqualTo(Duration.ofSeconds(expectedSeconds));
    }

    @Test
    void hugeAttemptCountsDoNotOverflow() {
        assertThat(POLICY.delayWithoutJitter(Integer.MAX_VALUE)).isEqualTo(Duration.ofMinutes(5));
        RetryPolicy long1 = new RetryPolicy(Duration.ofDays(1), Duration.ofDays(3650), 100, 0);
        assertThat(long1.delayWithoutJitter(1000)).isEqualTo(Duration.ofDays(3650));
        assertThat(long1.delayWithoutJitter(2)).isEqualTo(Duration.ofDays(2));
    }

    @Test
    void theDelayNeverDecreasesAndNeverExceedsTheCap() {
        Duration previous = Duration.ZERO;
        for (int failures = 1; failures <= 100; failures++) {
            Duration delay = POLICY.delayWithoutJitter(failures);
            assertThat(delay).isGreaterThanOrEqualTo(previous).isLessThanOrEqualTo(POLICY.maxDelay());
            previous = delay;
        }
    }

    @Test
    void aBaseDelayAboveHalfTheCapJumpsStraightToTheCap() {
        RetryPolicy policy = new RetryPolicy(Duration.ofSeconds(200), Duration.ofSeconds(300), 5, 0);

        assertThat(policy.delayWithoutJitter(1)).isEqualTo(Duration.ofSeconds(200));
        assertThat(policy.delayWithoutJitter(2)).isEqualTo(Duration.ofSeconds(300));
    }

    @Test
    void jitterOnlyShavesTheDelayWithinTheConfiguredFraction() {
        assertThat(POLICY.delayAfter(3, fixedRandom(0.0))).isEqualTo(Duration.ofSeconds(8));
        assertThat(POLICY.delayAfter(3, fixedRandom(0.5))).isEqualTo(Duration.ofMillis(7200));
        assertThat(POLICY.delayAfter(3, fixedRandom(0.999999)))
                .isBetween(Duration.ofMillis(6400), Duration.ofMillis(6401));
    }

    @Test
    void withRealRandomnessEveryDelayStaysInItsWindowAndTheyDiffer() {
        Random random = new Random(42);
        var delays = IntStream.range(0, 1000)
                .mapToObj(i -> POLICY.delayAfter(4, random))
                .toList();

        assertThat(delays).allSatisfy(d -> assertThat(d).isBetween(Duration.ofMillis(12800), Duration.ofSeconds(16)));
        assertThat(delays.stream().distinct().count()).isGreaterThan(100);
    }

    @Test
    void withoutJitterTheDelayIsExact() {
        RetryPolicy exact = new RetryPolicy(Duration.ofSeconds(1), Duration.ofSeconds(60), 3, 0);

        assertThat(exact.delayAfter(4, fixedRandom(0.99))).isEqualTo(Duration.ofSeconds(8));
    }

    @Test
    void nextAttemptIsNowPlusTheJitteredDelay() {
        Instant now = Instant.parse("2026-10-08T12:00:00Z");

        assertThat(POLICY.nextAttemptAfter(2, now, fixedRandom(0.0))).isEqualTo(now.plusSeconds(4));
    }

    @Test
    void exhaustionComesAfterMaxAttempts() {
        assertThat(POLICY.isExhaustedAfter(7)).isFalse();
        assertThat(POLICY.isExhaustedAfter(8)).isTrue();
        assertThat(POLICY.isExhaustedAfter(9)).isTrue();
    }

    @Test
    void theDefaultIsTwoSecondsToFiveMinutes() {
        assertThat(RetryPolicy.DEFAULT.baseDelay()).isEqualTo(Duration.ofSeconds(2));
        assertThat(RetryPolicy.DEFAULT.maxDelay()).isEqualTo(Duration.ofMinutes(5));
        assertThat(RetryPolicy.DEFAULT.maxAttempts()).isEqualTo(8);
    }

    @Test
    void refusesNonsense() {
        Duration one = Duration.ofSeconds(1);
        assertThatThrownBy(() -> new RetryPolicy(null, one, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(Duration.ZERO, one, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(Duration.ofSeconds(-1), one, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(Duration.ofSeconds(2), one, 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(one, null, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(one, one, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(one, one, 1, -0.1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(one, one, 1, 1.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(one, one, 1, Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> POLICY.delayWithoutJitter(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
