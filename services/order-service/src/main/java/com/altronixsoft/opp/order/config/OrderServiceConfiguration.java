package com.altronixsoft.opp.order.config;

import com.altronixsoft.opp.order.application.IdGenerator;
import com.github.f4b6a3.uuid.UuidCreator;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wiring of the technical ports of the application layer. */
@Configuration(proxyBeanMethods = false)
class OrderServiceConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    IdGenerator idGenerator() {
        return UuidCreator::getTimeOrderedEpoch;
    }
}
