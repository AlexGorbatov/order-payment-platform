package com.altronixsoft.opp.payment.config;

import com.altronixsoft.opp.payment.application.IdGenerator;
import com.github.f4b6a3.uuid.UuidCreator;
import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wiring of the technical ports of the application layer. */
@Configuration(proxyBeanMethods = false)
class PaymentServiceConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    IdGenerator idGenerator() {
        return UuidCreator::getTimeOrderedEpoch;
    }

    /** The jitter of retry backoff; shared across threads, hence the thread-local source. */
    @Bean
    RandomGenerator retryRandom() {
        return ThreadLocalRandom.current();
    }
}
