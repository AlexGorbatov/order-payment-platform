package com.altronixsoft.opp.payment.adapter.out.stripe;

import com.altronixsoft.opp.payment.application.StripeCallThrottle;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import java.time.Duration;

/**
 * {@link StripeCallThrottle} on a Resilience4j {@link RateLimiter}: at most {@code permitsPerSecond} calls per second,
 * waiting at most {@code maxWait} for a permit. Used for background work that may call Stripe in bulk (reconciliation),
 * so that it stays well below Stripe's rate limits (100 read requests per second in live mode, 25 in test mode).
 */
public final class StripeCallRateLimiter implements StripeCallThrottle {

    private final RateLimiter limiter;
    private final Duration maxWait;

    public StripeCallRateLimiter(String name, int permitsPerSecond, Duration maxWait) {
        if (permitsPerSecond < 1) {
            throw new IllegalArgumentException("permitsPerSecond must be at least 1");
        }
        this.maxWait = maxWait;
        this.limiter = RateLimiter.of(
                name,
                RateLimiterConfig.custom()
                        .limitForPeriod(permitsPerSecond)
                        .limitRefreshPeriod(Duration.ofSeconds(1))
                        .timeoutDuration(maxWait)
                        .build());
    }

    @Override
    public boolean tryAcquire() {
        return limiter.acquirePermission();
    }

    @Override
    public String toString() {
        return "StripeCallRateLimiter[" + limiter.getName() + ", "
                + limiter.getRateLimiterConfig().getLimitForPeriod() + "/s, maxWait=" + maxWait + "]";
    }
}
