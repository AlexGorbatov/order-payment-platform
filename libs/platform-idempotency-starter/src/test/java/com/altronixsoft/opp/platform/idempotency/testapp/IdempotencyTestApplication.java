package com.altronixsoft.opp.platform.idempotency.testapp;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/** Minimal service stand-in: the starter's auto-configuration, a demo controller, a meter registry and security. */
@SpringBootApplication
public class IdempotencyTestApplication {

    @Bean
    SimpleMeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }
}
