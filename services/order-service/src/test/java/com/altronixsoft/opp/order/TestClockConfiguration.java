package com.altronixsoft.opp.order;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the system clock of the service with a {@link MutableClock}; being primary, it wins every injection. */
@TestConfiguration(proxyBeanMethods = false)
class TestClockConfiguration {

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock();
    }
}
