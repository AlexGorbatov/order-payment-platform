package com.altronixsoft.opp.platform.messaging.outbox.testapp;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.test.simple.SimpleTracer;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/** Minimal service stand-in: only the starter's auto-configuration plus a meter registry and a tracer. */
@SpringBootApplication
public class OutboxTestApplication {

    @Bean
    SimpleMeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean
    SimpleTracer tracer() {
        return new SimpleTracer();
    }
}
