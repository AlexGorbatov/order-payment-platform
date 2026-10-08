package com.altronixsoft.opp.platform.messaging.outbox;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;

/** {@link TraceparentProvider} backed by Micrometer Tracing; only loaded when a tracing library is on the classpath. */
final class TraceparentHeader {

    private TraceparentHeader() {}

    static TraceparentProvider from(ObjectProvider<Tracer> tracer) {
        return () -> current(tracer.getIfAvailable());
    }

    /** {@code 00-<trace-id>-<span-id>-<flags>} for the current span; empty when there is no tracer or no span. */
    static Optional<String> current(Tracer tracer) {
        if (tracer == null) {
            return Optional.empty();
        }
        Span span = tracer.currentSpan();
        if (span == null) {
            return Optional.empty();
        }
        TraceContext context = span.context();
        String traceId = leftPad(context.traceId(), 32);
        String spanId = leftPad(context.spanId(), 16);
        String flags = Boolean.TRUE.equals(context.sampled()) ? "01" : "00";
        return Optional.of("00-" + traceId + "-" + spanId + "-" + flags);
    }

    private static String leftPad(String hex, int length) {
        return hex.length() >= length ? hex : "0".repeat(length - hex.length()) + hex;
    }
}
